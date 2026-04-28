import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import jdk.internal.org.objectweb.asm.*;

/**
 * Combined patch for Voxy deadlock fixes.
 *
 * Fix 1: IrisUtil - Prevent infinite recursion during shader reload
 * Fix 2: ActiveSectionTracker - Prevent StampedLock deadlock during shutdown
 *
 * Usage: java CombinedVoxyPatch <input.jar> <output.jar>
 */
public class CombinedVoxyPatch {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: CombinedVoxyPatch <input.jar> <output.jar>");
            return;
        }

        Path inputPath = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);

        if (!Files.exists(inputPath)) {
            System.err.println("Input jar not found: " + inputPath);
            return;
        }

        // Create temp directory for extracted classes
        Path tempDir = Files.createTempDirectory("voxy_patch");

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(inputPath));
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputPath))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if (name.equals("me/cortex/voxy/client/core/util/IrisUtil.class")) {
                    // Patch IrisUtil class
                    byte[] original = zis.readAllBytes();
                    byte[] patched = IrisUtilPatch.patch(original);
                    ZipEntry newEntry = new ZipEntry(name);
                    zos.putNextEntry(newEntry);
                    zos.write(patched);
                    zos.closeEntry();
                    System.out.println("Patched: IrisUtil.class");
                }
                else if (name.equals("me/cortex/voxy/common/world/ActiveSectionTracker.class")) {
                    // Patch ActiveSectionTracker class
                    byte[] original = zis.readAllBytes();
                    byte[] patched = ActiveSectionTrackerPatch.patch(original);
                    ZipEntry newEntry = new ZipEntry(name);
                    zos.putNextEntry(newEntry);
                    zos.write(patched);
                    zos.closeEntry();
                    System.out.println("Patched: ActiveSectionTracker.class");
                }
                else {
                    // Copy other entries unchanged
                    ZipEntry newEntry = new ZipEntry(name);
                    zos.putNextEntry(newEntry);
                    zos.write(zis.readAllBytes());
                    zos.closeEntry();
                }
            }
        }

        System.out.println("\nCombined patch applied successfully!");
        System.out.println("Output jar: " + outputPath);
        System.out.println("\nFixes applied:");
        System.out.println("1. IrisUtil: Added isReloading flag to prevent recursion");
        System.out.println("2. ActiveSectionTracker: Added engine.isLive check to prevent deadlock");
    }
}

/**
 * Patch for IrisUtil infinite recursion fix.
 */
class IrisUtilPatch extends ClassVisitor {
    private static final String CLASS_NAME = "me/cortex/voxy/client/core/util/IrisUtil";

    public IrisUtilPatch(ClassVisitor cv) {
        super(Opcodes.ASM7, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if ("voxypipelinepatch".equals(name) && "()V".equals(descriptor)) {
            return new MethodVisitor(Opcodes.ASM7, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitFieldInsn(Opcodes.GETSTATIC, CLASS_NAME, "isReloading", "Z");
                    Label skip = new Label();
                    super.visitJumpInsn(Opcodes.IFEQ, skip);
                    super.visitInsn(Opcodes.RETURN);
                    super.visitLabel(skip);
                }
            };
        }

        if ("reload0".equals(name) && "()V".equals(descriptor)) {
            return new MethodVisitor(Opcodes.ASM7, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitFieldInsn(Opcodes.GETSTATIC, CLASS_NAME, "isReloading", "Z");
                    Label skip = new Label();
                    super.visitJumpInsn(Opcodes.IFEQ, skip);
                    super.visitInsn(Opcodes.RETURN);
                    super.visitLabel(skip);
                    super.visitInsn(Opcodes.ICONST_1);
                    super.visitFieldInsn(Opcodes.PUTSTATIC, CLASS_NAME, "isReloading", "Z");
                }

                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN) {
                        super.visitInsn(Opcodes.ICONST_0);
                        super.visitFieldInsn(Opcodes.PUTSTATIC, CLASS_NAME, "isReloading", "Z");
                    }
                    super.visitInsn(opcode);
                }
            };
        }

        return mv;
    }

    @Override
    public void visitEnd() {
        FieldVisitor fv = cv.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                                         "isReloading", "Z", null, Boolean.FALSE);
        fv.visitEnd();
        super.visitEnd();
    }

    public static byte[] patch(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        IrisUtilPatch patch = new IrisUtilPatch(cw);
        cr.accept(patch, 0);
        return cw.toByteArray();
    }
}

/**
 * Patch for ActiveSectionTracker deadlock fix.
 */
class ActiveSectionTrackerPatch extends ClassVisitor {
    private static final String CLASS_NAME = "me/cortex/voxy/common/world/ActiveSectionTracker";
    private static final String ENGINE_CLASS = "me/cortex/voxy/common/world/WorldEngine";

    public ActiveSectionTrackerPatch(ClassVisitor cv) {
        super(Opcodes.ASM7, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // Patch acquire(long, boolean) method
        if ("acquire".equals(name) && "(JZ)Lme/cortex/voxy/common/world/WorldSection;".equals(descriptor)) {
            return new MethodVisitor(Opcodes.ASM7, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    // Check: if engine exists and !engine.isLive, return null
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitFieldInsn(Opcodes.GETFIELD, CLASS_NAME, "engine",
                        "L" + ENGINE_CLASS + ";");
                    Label skipCheck = new Label();
                    super.visitJumpInsn(Opcodes.IFNULL, skipCheck);

                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitFieldInsn(Opcodes.GETFIELD, CLASS_NAME, "engine",
                        "L" + ENGINE_CLASS + ";");
                    super.visitFieldInsn(Opcodes.GETFIELD, ENGINE_CLASS, "isLive", "Z");
                    Label isLive = new Label();
                    super.visitJumpInsn(Opcodes.IFNE, isLive);

                    // Return null if engine is not live
                    super.visitInsn(Opcodes.ACONST_NULL);
                    super.visitInsn(Opcodes.ARETURN);

                    super.visitLabel(isLive);
                    super.visitLabel(skipCheck);
                }
            };
        }

        // Patch acquire(int, int, int, int, boolean) method
        if ("acquire".equals(name) && "(IIIIZ)Lme/cortex/voxy/common/world/WorldSection;".equals(descriptor)) {
            return new MethodVisitor(Opcodes.ASM7, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitFieldInsn(Opcodes.GETFIELD, CLASS_NAME, "engine",
                        "L" + ENGINE_CLASS + ";");
                    Label skipCheck = new Label();
                    super.visitJumpInsn(Opcodes.IFNULL, skipCheck);

                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitFieldInsn(Opcodes.GETFIELD, CLASS_NAME, "engine",
                        "L" + ENGINE_CLASS + ";");
                    super.visitFieldInsn(Opcodes.GETFIELD, ENGINE_CLASS, "isLive", "Z");
                    Label isLive = new Label();
                    super.visitJumpInsn(Opcodes.IFNE, isLive);

                    super.visitInsn(Opcodes.ACONST_NULL);
                    super.visitInsn(Opcodes.ARETURN);

                    super.visitLabel(isLive);
                    super.visitLabel(skipCheck);
                }
            };
        }

        return mv;
    }

    // Custom ClassWriter that handles missing classes
    static class SafeClassWriter extends ClassWriter {
        public SafeClassWriter() {
            super(ClassWriter.COMPUTE_FRAMES);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            // Handle missing classes by returning java/lang/Object as common superclass
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (TypeNotPresentException e) {
                // If types are not present, assume Object is common superclass
                if (type1.equals(type2)) {
                    return type1;
                }
                return "java/lang/Object";
            }
        }
    }

    public static byte[] patch(byte[] original) {
        ClassReader cr = new ClassReader(original);
        SafeClassWriter cw = new SafeClassWriter();
        ActiveSectionTrackerPatch patch = new ActiveSectionTrackerPatch(cw);
        cr.accept(patch, 0);
        return cw.toByteArray();
    }
}