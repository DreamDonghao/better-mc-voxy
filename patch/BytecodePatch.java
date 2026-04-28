import java.io.*;
import java.nio.file.*;
import jdk.internal.org.objectweb.asm.*;

public class BytecodePatch extends ClassVisitor {
    private static final String CLASS_NAME = "me/cortex/voxy/client/core/util/IrisUtil";
    
    public BytecodePatch(ClassVisitor cv) {
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
        // 使用 COMPUTE_FRAMES 自动计算 StackMapTable
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        BytecodePatch patch = new BytecodePatch(cw);
        cr.accept(patch, 0);
        return cw.toByteArray();
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: BytecodePatch <input.class> <output.class>");
            return;
        }
        
        byte[] original = Files.readAllBytes(Paths.get(args[0]));
        byte[] patched = patch(original);
        Files.write(Paths.get(args[1]), patched);
        
        System.out.println("Patched successfully with COMPUTE_FRAMES!");
        System.out.println("Added: private static boolean isReloading = false;");
    }
}
