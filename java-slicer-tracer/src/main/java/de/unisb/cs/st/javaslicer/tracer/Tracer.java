package de.unisb.cs.st.javaslicer.tracer;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

import de.unisb.cs.st.javaslicer.tracer.classRepresentation.ReadClass;
import de.unisb.cs.st.javaslicer.tracer.classRepresentation.instructions.AbstractInstruction;
import de.unisb.cs.st.javaslicer.tracer.exceptions.TracerException;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.JSRInliner;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.PauseTracingInstrumenter;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.TracingClassInstrumenter;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.TracingMethodInstrumenter;
import de.unisb.cs.st.javaslicer.tracer.traceResult.TraceResult;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.TraceSequence;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.TraceSequenceFactory;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.uncompressed.UncompressedTraceSequenceFactory;
import de.unisb.cs.st.javaslicer.tracer.util.MultiplexedFileWriter;
import de.unisb.cs.st.javaslicer.tracer.util.SimpleArrayList;
import de.unisb.cs.st.javaslicer.tracer.util.WeakThreadMap;
import de.unisb.cs.st.javaslicer.tracer.util.MultiplexedFileWriter.MultiplexOutputStream;

public class Tracer implements ClassFileTransformer {

    private static final class FixedClassWriter extends ClassWriter {
        protected FixedClassWriter(final int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(final String type1, final String type2)
        {
            Class<?> c, d;
            try {
                c = Class.forName(type1.replace('/', '.'));
            } catch (final ClassNotFoundException e) {
                try {
                    c = ClassLoader.getSystemClassLoader().loadClass(type1.replace('/', '.'));
                } catch (final ClassNotFoundException e1) {
                    throw new RuntimeException(e1);
                }
            }
            try {
                d = Class.forName(type2.replace('/', '.'));
            } catch (final ClassNotFoundException e) {
                try {
                    d = ClassLoader.getSystemClassLoader().loadClass(type2.replace('/', '.'));
                } catch (final ClassNotFoundException e1) {
                    throw new RuntimeException(e1);
                }
            }
            if (c.isAssignableFrom(d)) {
                return type1;
            }
            if (d.isAssignableFrom(c)) {
                return type2;
            }
            if (c.isInterface() || d.isInterface()) {
                return "java/lang/Object";
            }
            do {
                c = c.getSuperclass();
            } while (!c.isAssignableFrom(d));
            return c.getName().replace('.', '/');
        }
    }

    private static final long serialVersionUID = 3853368930402145734L;

    private static Tracer instance = null;

    public static boolean debug = false;
    public static boolean check = false;

    private final String[] pauseTracingClasses = new String[] {
            "java.lang.ClassLoader",
            "sun.instrument.InstrumentationImpl"
    };
    // there are classes needed while retransforming.
    // these must be loaded a-priori, otherwise circular dependencies may occur
    private final String[] classesToPreload = {
    };
    private final String[] classesToPreloadIfChecking = {
            //"java.security.Policy",
            //"sun.security.provider.Sun",
            //"sun.misc.Cleaner",
    };
    private final String[] classesToPreloadIfDebug = {
    };


    public static final TraceSequenceFactory seqFactory = new UncompressedTraceSequenceFactory();
    //public static final TraceSequenceFactory seqFactory = new GZipTraceSequenceFactory();

    // TODO get rid of this list - it leads to linear memory consumption w.r.t. the thread count
    private final List<ThreadTracer> allThreadTracers = new SimpleArrayList<ThreadTracer>();
    private final Map<Thread, ThreadTracer> threadTracers = new WeakThreadMap<ThreadTracer>() {
            @Override
            protected void removing(final ThreadTracer value) {
                try {
                    value.finish();
                } catch (final IOException e) {
                    error(e);
                }
            }
        };

    private final List<ReadClass> readClasses = new ArrayList<ReadClass>();

    protected final List<TraceSequence.Type> traceSequenceTypes
        = new SimpleArrayList<TraceSequence.Type>();

    public volatile boolean tracingStarted = false;
    public volatile boolean tracingReady = false;

    private final File filename;
    private final MultiplexedFileWriter file;
    private final DataOutputStream mainOutStream;

    private Set<String> notRedefinedClasses;

    private static final AtomicInteger errorCount = new AtomicInteger(0);

    private static final boolean COMPUTE_FRAMES = false;
    private static String lastErrorString;


    private Tracer(final File filename) throws IOException {
        this.filename = filename;
        this.file = new MultiplexedFileWriter(filename, 512, 5);
        final MultiplexOutputStream stream = this.file.newOutputStream();
        if (stream.getId() != 0)
            throw new AssertionError("MultiplexedFileWriter does not initially return stream id 0");
        this.mainOutStream = new DataOutputStream(stream);
    }

    public static void error(final Exception e) {
        lastErrorString = e.toString();
        errorCount.getAndIncrement();
    }

    public static void newInstance(final File filename) throws IOException {
        if (instance != null)
            throw new IllegalStateException("Tracer instance already exists");
        instance = new Tracer(filename);
    }

    public static Tracer getInstance() {
        if (instance == null)
            throw new IllegalStateException("Tracer instance not created");
        return instance;
    }

    public void add(final Instrumentation inst, final boolean retransformClasses) throws TracerException {

        // check the JRE version we run on
        final String javaVersion = System.getProperty("java.version");
        final int secondPointPos = javaVersion.indexOf('.', javaVersion.indexOf('.')+1);
        try {
            if (secondPointPos != -1) {
                final double javaVersionDouble = Double.valueOf(javaVersion.substring(0, secondPointPos));
                if (javaVersionDouble < 1.59) {
                    System.err.println("This tracer requires JRE >= 1.6, you are running " + javaVersion + ".");
                    System.exit(-1);
                }
            }
        } catch (final NumberFormatException e) {
            // ignore (no check...)
        }

        if (retransformClasses && !inst.isRetransformClassesSupported())
            throw new TracerException("Your JVM does not support retransformation of classes");

        final List<Class<?>> additionalClassesToRetransform = new ArrayList<Class<?>>();
        for (final String classname: this.classesToPreload) {
            Class<?> class1;
            try {
                class1 = ClassLoader.getSystemClassLoader().loadClass(classname);
            } catch (final ClassNotFoundException e) {
                continue;
            }
            additionalClassesToRetransform.add(class1);
        }
        if (check) {
            for (final String classname: this.classesToPreloadIfChecking) {
                Class<?> class1;
                try {
                    class1 = ClassLoader.getSystemClassLoader().loadClass(classname);
                } catch (final ClassNotFoundException e) {
                    continue;
                }
                additionalClassesToRetransform.add(class1);
            }
        }
        if (debug) {
            for (final String classname: this.classesToPreloadIfDebug) {
                Class<?> class1;
                try {
                    class1 = ClassLoader.getSystemClassLoader().loadClass(classname);
                } catch (final ClassNotFoundException e) {
                    continue;
                }
                additionalClassesToRetransform.add(class1);
            }
        }

        this.notRedefinedClasses = new HashSet<String>();
        for (final Class<?> class1: additionalClassesToRetransform)
            this.notRedefinedClasses.add(class1.getName());
        for (final Class<?> class1: inst.getAllLoadedClasses())
            this.notRedefinedClasses.add(class1.getName());

        inst.addTransformer(this, true);

        if (retransformClasses) {
            final ArrayList<Class<?>> classesToTransform = new ArrayList<Class<?>>();
            for (final Class<?> class1: inst.getAllLoadedClasses()) {
                final boolean isModifiable = inst.isModifiableClass(class1);
                if (debug && !isModifiable && !class1.isPrimitive() && !class1.isArray())
                    System.out.println("not modifiable: " + class1);
                boolean modify = isModifiable && !class1.isInterface();
                modify &= !class1.getName().startsWith("de.unisb.cs.st.javaslicer.tracer");
                if (modify)
                    classesToTransform.add(class1);
            }
            for (final Class<?> class1: additionalClassesToRetransform) {
                if (!classesToTransform.contains(class1)) {
                    classesToTransform.add(class1);
                }
            }

            if (debug) {
                System.out.println("classes to transform:");
                System.out.println("############################################");
                System.out.println("############################################");
                System.out.println("############################################");
                for (final Class<?> c1 : classesToTransform) {
                    System.out.println(c1);
                }
                System.out.println("############################################");
                System.out.println("############################################");
                System.out.println("############################################");
            }

            try {
                inst.retransformClasses(classesToTransform.toArray(new Class<?>[classesToTransform.size()]));
                if (debug)
                    System.out.println("Instrumentation ready");
            } catch (final UnmodifiableClassException e) {
                throw new TracerException(e);
            }

            if (debug) {
                // print statistics once now and once when all finished
                TracingMethodInstrumenter.printStats(System.out);
                Runtime.getRuntime().addShutdownHook(new Thread() {
                    @Override
                    public void run() {
                        TracingMethodInstrumenter.printStats(System.out);
                    }
                });
            }
        }

        synchronized (this) {
            this.tracingStarted = true;
            for (final ThreadTracer tt: this.allThreadTracers)
                tt.unpauseTracing();
        }
    }

    public synchronized byte[] transform(final ClassLoader loader, final String className,
            final Class<?> classBeingRedefined, final ProtectionDomain protectionDomain, final byte[] classfileBuffer) {

        if (this.tracingReady)
            return null;

        // disable tracing for the thread tracer of this thread
        final ThreadTracer tt = getThreadTracer(Thread.currentThread());
        tt.pauseTracing();

        try {
            if (Type.getObjectType(className).getClassName().startsWith(Tracer.class.getPackage().getName()))
                return null;

            //////////////////////////////////////////////////////////////////
            // NOTE: these will be cleaned up when the system runs stable
            //////////////////////////////////////////////////////////////////

            /*
            if (!className.equals("Test"))
                return null;
            */

            if (Type.getObjectType(className).getClassName().equals("java.lang.System"))
                return null;
            if (Type.getObjectType(className).getClassName().equals("java.lang.VerifyError"))
                return null;

            if (Type.getObjectType(className).getClassName().startsWith("java.util.Collections"))
                return null;

            // Thread, ThreadLocal and ThreadLocalMap
            if (Type.getObjectType(className).getClassName().startsWith("java.lang.Thread"))
                return null;
            // because of Thread.getName()
            if (Type.getObjectType(className).getClassName().equals("java.lang.String"))
                return null;
            if (Type.getObjectType(className).getClassName().equals("java.util.Arrays"))
                return null;
            if (Type.getObjectType(className).getClassName().equals("java.lang.Math"))
                return null;

            // Object
            if (Type.getObjectType(className).getClassName().equals("java.lang.Object"))
                return null;
            if (Type.getObjectType(className).getClassName().startsWith("java.lang.ref."))
                return null;

            final ClassReader reader = new ClassReader(classfileBuffer);

            final ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            // register that class for later reconstruction of the trace
            final ReadClass readClass = new ReadClass(className, AbstractInstruction.getNextIndex(), classNode.access);
            this.readClasses.add(readClass);

            //final boolean computeFrames = COMPUTE_FRAMES || Arrays.asList(this.pauseTracingClasses).contains(Type.getObjectType(className).getClassName());
            final boolean computeFrames = COMPUTE_FRAMES;

            final ClassWriter writer = new FixedClassWriter(computeFrames ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);

            final ClassVisitor output = check ? new CheckClassAdapter(writer) : writer;

            if (Arrays.asList(this.pauseTracingClasses).contains(Type.getObjectType(className).getClassName())) {
                new PauseTracingInstrumenter(readClass, this).transform(classNode);
            } else {
                new TracingClassInstrumenter(readClass, this).transform(classNode);
            }

            classNode.accept(computeFrames ? new JSRInliner(output) : output);

            readClass.setInstructionNumberEnd(AbstractInstruction.getNextIndex());

            final byte[] newClassfileBuffer = writer.toByteArray();

            if (check) {
                checkClass(newClassfileBuffer, readClass.getClassName());
            }

            //printClass(newClassfileBuffer, Type.getObjectType(className).getClassName());
            /*
            if (className.equals("java/lang/ClassLoader"))
                printClass(newClassfileBuffer, Type.getObjectType(className).getClassName());
            if (className.equals("java/util/zip/ZipFile"))
                printClass(newClassfileBuffer, Type.getObjectType(className).getClassName());
            */

            return newClassfileBuffer;
        } catch (final Throwable t) {
            System.err.println("Error transforming class " + className + ":");
            t.printStackTrace(System.err);
            return null;
        } finally {
            tt.unpauseTracing();
        }
    }

    public int getNextSequenceIndex() {
        return this.traceSequenceTypes.size();
    }

    private boolean checkClass(final byte[] newClassfileBuffer, final String classname) {
        final ClassNode cn = new ClassNode();
        final ClassReader cr = new ClassReader(newClassfileBuffer);
        //cr.accept(new CheckClassAdapter(cn), ClassReader.SKIP_DEBUG);
        cr.accept(new CheckClassAdapter(cn), 0);

        for (final Object methodObj : cn.methods) {
            final MethodNode method = (MethodNode) methodObj;
            final Analyzer a = new Analyzer(new BasicVerifier());
            //final Analyzer a = new Analyzer(new SimpleVerifier());
            try {
                a.analyze(cn.name, method);
            } catch (final AnalyzerException e) {
                System.err.println("Error in method " + classname + "." + method.name
                        + method.desc + ":");
                e.printStackTrace(System.err);
                printMethod(a, System.err, method);
                return false;
            }
        }
        return true;
    }

    private void printMethod(final Analyzer a, final PrintStream out, final MethodNode method) {
        final Frame[] frames = a.getFrames();

        final TraceMethodVisitor mv = new TraceMethodVisitor();

        out.println(method.name + method.desc);
        for (int j = 0; j < method.instructions.size(); ++j) {
            method.instructions.get(j).accept(mv);

            final StringBuffer s = new StringBuffer();
            final Frame f = frames[j];
            if (f == null) {
                s.append('?');
            } else {
                for (int k = 0; k < f.getLocals(); ++k) {
                    s.append(getShortName(f.getLocal(k).toString())).append(' ');
                }
                s.append(" : ");
                for (int k = 0; k < f.getStackSize(); ++k) {
                    s.append(getShortName(f.getStack(k).toString())).append(' ');
                }
            }
            while (s.length() < method.maxStack + method.maxLocals + 1) {
                s.append(' ');
            }
            out.print(Integer.toString(j + 100000).substring(1));
            out.print(" " + s + " : " + mv.text.get(j));
        }
        for (int j = 0; j < method.tryCatchBlocks.size(); ++j) {
            ((TryCatchBlockNode) method.tryCatchBlocks.get(j)).accept(mv);
            out.print(" " + mv.text.get(j));
        }
        out.println(" MAXSTACK " + method.maxStack);
        out.println(" MAXLOCALS " + method.maxLocals);
        out.println();
    }

    @SuppressWarnings("unused")
    private void printClass(final byte[] classfileBuffer, final String classname) {
        final TraceClassVisitor v = new TraceClassVisitor(new PrintWriter(System.out));
        new ClassReader(classfileBuffer).accept(v, ClassReader.SKIP_DEBUG);
    }

    private static String getShortName(final String name) {
        final int n = name.lastIndexOf('/');
        int k = name.length();
        if (name.charAt(k - 1) == ';') {
            k--;
        }
        return n == -1 ? name : name.substring(n + 1, k);
    }

    public int newIntegerTraceSequence() {
        return newTraceSequence(TraceSequence.Type.INTEGER);
    }

    public int newLongTraceSequence() {
        return newTraceSequence(TraceSequence.Type.LONG);
    }

    public int newObjectTraceSequence() {
        return newTraceSequence(TraceSequence.Type.OBJECT);
    }

    private synchronized int newTraceSequence(final TraceSequence.Type type) {
        final int nextIndex = getNextSequenceIndex();
        this.traceSequenceTypes.add(type);
        return nextIndex;
    }

    public synchronized ThreadTracer getThreadTracer(final Thread thread) {
        final ThreadTracer tracer = this.threadTracers.get(thread);
        if (tracer != null)
            return tracer;
        final ThreadTracer newTracer = new ThreadTracer(thread,
                Tracer.this.traceSequenceTypes, this);
        this.threadTracers.put(thread, newTracer);
        if (!this.tracingStarted || this.tracingReady)
            newTracer.pauseTracing();
        if (!this.tracingReady)
            this.allThreadTracers.add(newTracer);
        return newTracer;
    }

    public void finish() throws IOException {
        if (this.tracingReady)
            return;
        this.tracingReady = true;
        final ThreadTracer[] tmp = this.allThreadTracers.toArray(new ThreadTracer[this.allThreadTracers.size()]);
        for (final ThreadTracer t: tmp)
            t.finish();
        this.mainOutStream.writeInt(this.readClasses.size());
        for (final ReadClass rc: this.readClasses)
            rc.writeOut(this.mainOutStream);
        this.mainOutStream.writeInt(tmp.length);
        for (final ThreadTracer t: tmp)
            t.writeOut(this.mainOutStream);
        this.mainOutStream.close();
        this.file.close();
    }

    public TraceResult getResult() throws IOException {
        finish();
        return TraceResult.readFrom(this.filename);
    }

    public MultiplexOutputStream newOutputStream() {
        return this.file.newOutputStream();
    }

    public static void printFinalUserInfo() {
        if (errorCount.get() == 1) {
            System.out.println("There was an error while tracing: " + lastErrorString);
        } else if (errorCount.get() > 1) {
            System.out.println("There were several errors (" + errorCount.get() + " while tracing.");
            System.out.println("Last error message: " + lastErrorString);
        } else {
            if (Tracer.debug)
                System.out.println("DEBUG: trace written successfully");
        }
    }

    public boolean wasRedefined(final String className) {
        return !this.notRedefinedClasses.contains(className);
    }

}
