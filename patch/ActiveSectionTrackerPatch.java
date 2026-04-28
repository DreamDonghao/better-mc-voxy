import java.io.*;
import java.nio.file.*;
import jdk.internal.org.objectweb.asm.*;

/**
 * Patch for ActiveSectionTracker deadlock fix.
 *
 * Problem: When shaders reload, Render thread waits for ChunkBuilder.shutdownThreads()
 * to complete via Thread.join(). Worker threads are blocked on StampedLock.readLock()
 * in ActiveSectionTracker.acquire(), creating a deadlock.
 *
 * Solution: Add check for engine.isLive at the start of acquire() method.
 * If engine is not live, return null immediately instead of blocking on locks.
 */
public class ActiveSectionTrackerPatch extends ClassVisitor {
    private static final String CLASS_NAME = "me/cortex/voxy/common/world/ActiveSectionTracker";
    private static final String ENGINE_CLASS = "me/cortex/voxy/common/world/WorldEngine";

    public ActiveSectionTrackerPatch(ClassVisitor cv) {
        super(Opcodes.ASM7, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // Patch the acquire(long, boolean) method - this is the main entry point
        if ("acquire".equals(name) && "(JZ)Lme/cortex/voxy/common/world/WorldSection;".equals(descriptor)) {
            return new MethodVisitor(Opcodes.ASM7, mv) {
                private boolean injectedCheck = false;

                @Override
                public void visitCode() {
                    super.visitCode();
                    // Inject check at the very beginning
                    // Check: if (engine != null && !engine.isLive) return null;
                    super.visitVarInsn(Opcodes.ALOAD, 0);  // Load 'this'
                    super.visitFieldInsn(Opcodes.GETFIELD, CLASS_NAME, "engine",
                        "L" + ENGINE_CLASS + ";");
                    Label engineNotNull = new Label();
                    super.visitJumpInsn(Opcodes.IFNONNULL, engineNotNull);
                    // Engine is null, continue with original logic
                    Label skipReturnNull = new Label();
                    super.visitJumpInsn(Opcodes.GOTO, skipReturnNull);

                    super.visitLabel(engineNotNull);
                    super.visitVarInsn(Opcodes.ALOAD, 0);  // Load 'this' again
                    super.visitFieldInsn(Opcodes.GETFIELD, CLASS_NAME, "engine",
                        "L" + ENGINE_CLASS + ";");
                    super.visitFieldInsn(Opcodes.GETFIELD, ENGINE_CLASS, "isLive", "Z");
                    Label engineIsLive = new Label();
                    super.visitJumpInsn(Opcodes.IFNE, engineIsLive);
                    // Engine is not live, return null
                    super.visitInsn(Opcodes.ACONST_NULL);
                    super.visitInsn(Opcodes.ARETURN);

                    super.visitLabel(engineIsLive);
                    super.visitLabel(skipReturnNull);
                    injectedCheck = true;
                }
            };
        }

        // Also patch the acquire(int, int, int, int, boolean) method
        if ("acquire".equals(name) && "(IIIIZ)Lme/cortex/voxy/common/world/WorldSection;".equals(descriptor)) {
            return new MethodVisitor(Opcodes.ASM7, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    // This method calls acquire(long, boolean), so the check will be performed there
                    // But we also add a check here for safety
                    super.visitVarInsn(Opcodes.ALOAD, 0);  // Load 'this'
                    super.visitFieldInsn(Opcodes.GETFIELD, CLASS_NAME, "engine",
                        "L" + ENGINE_CLASS + ";");
                    Label skipCheck = new Label();
                    super.visitJumpInsn(Opcodes.IFNULL, skipCheck);

                    super.visitVarInsn(Opcodes.ALOAD, 0);  // Load 'this' again
                    super.visitFieldInsn(Opcodes.GETFIELD, CLASS_NAME, "engine",
                        "L" + ENGINE_CLASS + ";");
                    super.visitFieldInsn(Opcodes.GETFIELD, ENGINE_CLASS, "isLive", "Z");
                    Label isLive = new Label();
                    super.visitJumpInsn(Opcodes.IFNE, isLive);
                    // Engine is not live, return null
                    super.visitInsn(Opcodes.ACONST_NULL);
                    super.visitInsn(Opcodes.ARETURN);

                    super.visitLabel(isLive);
                    super.visitLabel(skipCheck);
                }
            };
        }

        return mv;
    }

    public static byte[] patch(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ActiveSectionTrackerPatch patch = new ActiveSectionTrackerPatch(cw);
        cr.accept(patch, 0);
        return cw.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: ActiveSectionTrackerPatch <input.class> <output.class>");
            return;
        }

        byte[] original = Files.readAllBytes(Paths.get(args[0]));
        byte[] patched = patch(original);
        Files.write(Paths.get(args[1]), patched);

        System.out.println("Patched ActiveSectionTracker successfully!");
        System.out.println("Added engine.isLive check at start of acquire() methods");
    }
}