/*
 * Copyright (c) 2025, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package unreflect;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.incubator.code.CodeTransformer;
import jdk.incubator.code.Op;
import jdk.incubator.code.bytecode.BytecodeGenerator;
import jdk.incubator.code.dialect.core.CoreOp;
import jdk.incubator.code.dialect.core.CoreType;
import jdk.incubator.code.dialect.java.JavaOp;
import jdk.incubator.code.dialect.java.JavaType;
import jdk.incubator.code.dialect.java.MethodRef;
import jdk.incubator.code.runtime.ReflectableLambdaMetafactory;

public final class Unreflect {

    static final ClassDesc CD_Unreflect = Unreflect.class.describeConstable().get();
    static final ClassDesc CD_ReflectableLambdaMetafactory = ReflectableLambdaMetafactory.class.describeConstable().get();
    static final MethodTypeDesc MTD_Op = MethodTypeDesc.of(Op.class.describeConstable().get());

    private static String toOpMethodName(ClassDesc thisClass, MethodModel mm) {
        // replicate method lookup from Op::createCodeModel
        MethodTypeDesc mtd = mm.methodTypeSymbol();
        MethodRef mref = MethodRef.method(JavaType.type(thisClass),
                                          mm.methodName().stringValue(),
                                          JavaType.type(mtd.returnType()),
                                          mtd.parameterList().stream().map(JavaType::type).toList());
        char[] sig = mref.toString().toCharArray();
        for (int i = 0; i < sig.length; i++) {
            switch (sig[i]) {
                case '.', ';', '[', '/': sig[i] = '$';
            }
        }
        return new String(sig);
    }

    private static void transform(Path classFile) {
        try {
            ClassModel clm = ClassFile.of().parse(Files.readAllBytes(classFile));
            System.out.println(clm.thisClass().asInternalName());
            Set<String> opMethods = clm.methods().stream().filter(mm -> mm.methodTypeSymbol().equals(MTD_Op)).map(mm -> mm.methodName().stringValue()).collect(Collectors.toSet());
            Files.write(classFile, ClassFile.of(ClassFile.ConstantPoolSharingOption.NEW_POOL).transformClass(clm, (clb, cle) -> {
                if (cle instanceof MethodModel mm) {
                    if (opMethods.contains(toOpMethodName(clm.thisClass().asSymbol(), mm))) { // look for related op method, do not depend on @Reflect annotation
                        System.out.println("  " + mm.methodName() + mm.methodTypeSymbol().displayDescriptor());
                        clb.transformMethod(mm, MethodTransform.dropping(me -> me instanceof CodeModel)
                                .andThen(MethodTransform.endHandler(mb -> mb.withCode(cob -> {
                                    MethodTypeDesc mts = mm.methodTypeSymbol();
                                    boolean hasReceiver = !mm.flags().has(AccessFlag.STATIC);
                                    if (hasReceiver) {
                                        cob.aload(cob.receiverSlot());
                                    }
                                    for (int i = 0; i < mts.parameterCount(); i++) {
                                        cob.loadLocal(TypeKind.from(mts.parameterType(i)), cob.parameterSlot(i));
                                    }
                                    cob.invokedynamic(DynamicCallSiteDesc.of(
                                            ConstantDescs.ofCallsiteBootstrap(CD_Unreflect, "unreflect", ConstantDescs.CD_CallSite),
                                            mm.methodName().stringValue(),
                                            hasReceiver ? mts.insertParameterTypes(0, clm.thisClass().asSymbol()) : mts));
                                    cob.return_(TypeKind.from(mts.returnType()));
                                }))));
                    } else {
                        clb.transformMethod(mm, MethodTransform.transformingCode((cob, coe) -> {
                            DirectMethodHandleDesc bsm;
                            if (coe instanceof InvokeDynamicInstruction i
                                    && (bsm = i.bootstrapMethod()).owner().equals(CD_ReflectableLambdaMetafactory)) {
                                System.out.println("  " + i.name());
                                // redirect metafactory and altMetafactory
                                cob.invokedynamic(DynamicCallSiteDesc.of(
                                        MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
                                                                  CD_Unreflect,
                                                                  bsm.methodName(),
                                                                  bsm.invocationType()),
                                        i.name().stringValue(),
                                        MethodTypeDesc.ofDescriptor(i.type().stringValue()),
                                        i.bootstrapArgs().toArray(ConstantDesc[]::new)));
                            } else {
                                cob.with(coe);
                            }
                        }));
                    }
                } else {
                    clb.with(cle);
                }
            }));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static CallSite unreflect(MethodHandles.Lookup caller,
                                     String methodName,
                                     MethodType methodType) throws NoSuchMethodException {
        for (Method m : caller.lookupClass().getDeclaredMethods()) {
            int firstParam = (m.getModifiers() & Modifier.STATIC) == 0 ? 1 : 0;
            if (m.getName().equals(methodName)
                    && m.getReturnType() == methodType.returnType()
                    && m.getParameterCount() == methodType.parameterCount() - firstParam
                    && Arrays.equals(m.getParameterTypes(), 0, m.getParameterCount(),
                                     methodType.parameterArray(), firstParam, methodType.parameterCount())) {
                return new ConstantCallSite(BytecodeGenerator.generate(caller, Op.ofMethod(m).orElseThrow()));
            }
        }
        throw new NoSuchMethodException(caller.lookupClass().getName() + "." + methodName + methodType);
    }

    public static CallSite metafactory(MethodHandles.Lookup caller,
                                       String interfaceMethodName,
                                       MethodType factoryType,
                                       MethodType interfaceMethodType,
                                       MethodHandle implementation,
                                       MethodType dynamicMethodType) throws LambdaConversionException {
        return ReflectableLambdaMetafactory.metafactory(caller,
                                                        interfaceMethodName,
                                                        factoryType,
                                                        interfaceMethodType,
                                                        unreflectLambdaImplementation(caller, interfaceMethodName),
                                                        dynamicMethodType);
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller,
                                          String interfaceMethodName,
                                          MethodType factoryType,
                                          Object... args) throws LambdaConversionException {
        args[1] = unreflectLambdaImplementation(caller, interfaceMethodName);
        return ReflectableLambdaMetafactory.altMetafactory(caller,
                                                           interfaceMethodName,
                                                           factoryType,
                                                           args);
    }

    private static MethodHandle unreflectLambdaImplementation(MethodHandles.Lookup caller, String interfaceMethodName)
            throws LambdaConversionException {
        try {
            MethodHandle opHandle = caller.findStatic(caller.lookupClass(),
                                                      interfaceMethodName.split("=")[1],
                                                      MethodType.methodType(Op.class));
            return BytecodeGenerator.generate(caller, unquoteLambda((CoreOp.FuncOp)opHandle.invoke()));
        } catch (Throwable t) {
            throw new LambdaConversionException(t);
        }
    }

    // flat QuotedOp and LambdaOp
    private static CoreOp.FuncOp unquoteLambda(CoreOp.FuncOp funcOp) {
        int capturedValues = funcOp.parameters().size();
        List<Op> ops = funcOp.body().entryBlock().ops();
        JavaOp.LambdaOp lambda = (JavaOp.LambdaOp)((CoreOp.QuotedOp)ops.get(ops.size() - 2)).quotedOp();
        return CoreOp.func(funcOp.funcName(), CoreType.functionType(
                lambda.body().yieldType(),
                Stream.of(funcOp.invokableType().parameterTypes(),
                          lambda.invokableType().parameterTypes()).flatMap(List::stream).toList())).body(bb -> {
            bb.context().mapBlock(funcOp.body().entryBlock(), bb.entryBlock());
            bb.context().mapValues(funcOp.parameters(), bb.parameters().subList(0, capturedValues));
            for (int i = 0; i < ops.size() - 2; i++) {
                Op o = ops.get(i);
                bb.context().mapValue(o.result(), bb.op(o));
            }
            bb.body(lambda.body(),
                    bb.parameters().subList(capturedValues, bb.parameters().size()),
                    bb.context(),
                    CodeTransformer.COPYING_TRANSFORMER);
        });
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Missing root folder argument");
        Path root = Path.of(args[0]);
        Files.find(root, Integer.MAX_VALUE, (p, a) -> a.isRegularFile() && p.toString().endsWith(".class")).forEach(Unreflect::transform);
        Files.write(Files.createDirectories(root.resolve("unreflect")).resolve("Unreflect.class"), Unreflect.class.getResourceAsStream("Unreflect.class").readAllBytes());
    }
}
