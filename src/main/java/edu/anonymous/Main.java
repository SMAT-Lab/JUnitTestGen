package edu.anonymous;

import com.opencsv.CSVWriter;
import edu.anonymous.model.APITestCaseModel;
import edu.anonymous.model.APIType;
import edu.anonymous.model.LocalModel;
import edu.anonymous.model.Parameter;
import edu.anonymous.utils.ApplicationClassFilter;
import edu.anonymous.utils.Rgex;
import edu.psu.cse.siis.coal.*;
import edu.psu.cse.siis.coal.arguments.ArgumentValueManager;
import edu.psu.cse.siis.coal.field.transformers.FieldTransformerManager;
import edu.psu.cse.siis.coal.lang.ParseException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import soot.*;
import soot.dava.internal.javaRep.DIntConstant;
import soot.jimple.*;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.infoflow.android.config.SootConfigForAndroid;
import soot.jimple.internal.*;
import soot.jimple.toolkits.annotation.j5anno.AnnotationGenerator;
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG;
import soot.jimple.toolkits.typing.fast.Integer127Type;
import soot.jimple.toolkits.typing.fast.Integer1Type;
import soot.jimple.toolkits.typing.fast.Integer32767Type;
import soot.options.Options;
import soot.tagkit.AnnotationClassElem;
import soot.tagkit.AnnotationElem;
import soot.toolkits.graph.DirectedGraph;
import soot.util.Chain;
import soot.util.JasminOutputStream;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) throws IOException, ParseException, ClassNotFoundException {

        String apkPath = args[0];
        String forceAndroidJar = args[1];
        String utNameAPISigCsvPath = args[2];
        //String neo4jDataPath = args[3];

        long startTime = System.currentTimeMillis();
        System.out.println("==>START TIME:" + startTime);

        //init
        JimpleBasedInterproceduralCFG iCfg = initialize(apkPath, forceAndroidJar);
        long initTime = System.currentTimeMillis();
        System.out.println("==>AFTER INIT TIME:" + initTime);

        //collect MinContext for each Android Framework API
        collectMinContext4FrameworkAPI(iCfg);

        long afterMinContextTime = System.currentTimeMillis();
        System.out.println("==>ATFER MIN CONTEXT GENERATION TIME:" + afterMinContextTime);

        printTestCaseModelList();

        System.out.println("GlobalRef.apiTestCaseModelList Num:" + GlobalRef.apiTestCaseModelList.size());

        for (APITestCaseModel caseModel : GlobalRef.apiTestCaseModelList) {
            boolean isTestCaseGenerateSuccess = false;
            try {
                if(caseModel.unit.toString().contains("android.os.Looper: void loop()")){
                    continue;
                }
                isTestCaseGenerateSuccess = generateTestCase(caseModel);
            } catch (Exception e) {
                //System.out.println(e);
            }

            if (isTestCaseGenerateSuccess) {
                //output utName-APISig to csv;[example: TestCase_905a4f82bc194334a046afa9bf29eed7__738686647|<android.text.TextUtils: boolean equals(java.lang.CharSequence,java.lang.CharSequence)>]
                CSVWriter utNameAPISigWriter = new CSVWriter(new FileWriter(utNameAPISigCsvPath, true));
                String[] utNameAPIRow = {caseModel.sootClassName, caseModel.apiSignature};
                utNameAPISigWriter.writeNext(utNameAPIRow);
                utNameAPISigWriter.close();
            }
        }

        long finalTime = System.currentTimeMillis();
        System.out.println("==>FINAL TIME:" + finalTime);

    }

    private static void collectMinContext4FrameworkAPI(JimpleBasedInterproceduralCFG iCfg) {
        Scene.v().getApplicationClasses().forEach(aClass -> {

            aClass.getMethods().stream().filter(am -> {
                return am.isConcrete()
                        && !ApplicationClassFilter.isAndroidSystemPackage(am.getDeclaringClass().getName())
                        && !am.getSignature().contains("dummyMainClass");
            }).forEach(targetMethod -> {
                DirectedGraph<Unit> ug = iCfg.getOrCreateUnitGraph(targetMethod.retrieveActiveBody());
                Iterator<Unit> uit = ug.iterator();
                List<Unit> units = new ArrayList<>();
                uit.forEachRemaining(units::add);

                for (int i = 0; i < units.size(); i++) {
                    try {
                        Unit u = units.get(i);

                        if (ApplicationClassFilter.isAndroidLifeCycleMethod(u.toString()) || targetMethod.toString().contains("<init>")) {
                            continue;
                        }
                        //min Context Analysis
                        calculateMinContext(targetMethod, units, i);
                    } catch (StackOverflowError e) {
                        System.out.println("[Continue]StackOverflowError:" + units.get(i).toString() + "；target Method:" + targetMethod.getSignature());
                        continue;
                    } catch (Exception e) {
                        System.out.println("[Continue]Exception:" + units.get(i).toString() + "；target Method:" + targetMethod.getSignature());
                        continue;
                    }
                }
            });
        });
    }

    private static boolean generateTestCase(APITestCaseModel caseModel) throws IOException, ClassNotFoundException {
        SootClass sClass;
        SootMethod testMethod;

        // Resolve dependencies
        Scene.v().loadClassAndSupport("java.lang.Object");
        Scene.v().loadClassAndSupport("java.lang.System");

        // Declare 'public class HelloWorld'
        String sClassName = ("TestCase_" + GlobalRef.apkName + "_" + caseModel.unit.toString().hashCode()).replace(".", "_").replace("-", "_") + "Test";
        sClass = new SootClass(sClassName, Modifier.PUBLIC);
        caseModel.sootClassName = sClassName;

        // add RunWith annotation to Test Class
        List<AnnotationElem> annotationElems = new ArrayList<>();
        AnnotationClassElem annotationElem = new AnnotationClassElem(Scene.v().getSootClass("androidx.test.runner.AndroidJUnit4").getName(), "c".charAt(0), "value");
        annotationElems.add(annotationElem);
        AnnotationGenerator.v().annotate(sClass, RunWith.class, annotationElems);

        // 'extends Object'
        sClass.setSuperclass(Scene.v().getSootClass("java.lang.Object"));
        Scene.v().addClass(sClass);

        // Create the before method body
        {
            // Create the test method, public void test(){}
            if (APIType.toStringType(caseModel.apiType).equals("STATIC_INVOKE")) {
                testMethod = new SootMethod("testCase", Arrays.asList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
            } else {
                testMethod = new SootMethod("testCase", Arrays.asList(), VoidType.v(), Modifier.PUBLIC);
                AnnotationGenerator.v().annotate(testMethod, Test.class, new ArrayList<>());
            }

            sClass.addMethod(testMethod);

            //Add "throws Exception" to testMethod
            testMethod.addException(Scene.v().getSootClass("java.lang.Exception"));

            // create empty body
            JimpleBody body = Jimple.v().newBody(testMethod);

            testMethod.setActiveBody(body);
            Chain units = body.getUnits();

            //duplicate caseModel.baseBoxStmts
            duplicate(caseModel);

            //replaceAPKClasses
            replaceAPKClasses(caseModel);

            //add Activity architecture
            processActivityArchitecture(caseModel, sClass);

            //add system.out method
            SootMethod systemOutMethod = processOutMethod(sClass);

            //add Static architecture
            processStaticArchitecture(caseModel, sClass, testMethod);

            //add this identity
            addThisIdentityStmt(caseModel, sClass, body, units);

            //process Parameter
            processContextAndParameter(caseModel, body, units);

            //process super.method() invocation
            processSuperMethodInvocation(caseModel);

            //Add test-case related locals & units[in BaseBox Min Context]
            if (CollectionUtils.isNotEmpty(caseModel.baseBoxStmts)) {
                for (int i = caseModel.baseBoxStmts.size() - 1; i >= 0; i--) {
                    if (!(caseModel.baseBoxStmts.get(i) instanceof Stmt)) {
                        continue;
                    }
                    Stmt stmt = (Stmt) caseModel.baseBoxStmts.get(i);

                    for (ValueBox vb : stmt.getUseAndDefBoxes()) {
                        if ((vb.getValue() instanceof JimpleLocal) && !body.getLocals().contains((JimpleLocal) vb.getValue())) {
                            body.getLocals().add((JimpleLocal) vb.getValue());
                        }
                        //convert Filed local to the thisLocal to pass SootLocalValid
                        processFiledLocal(body, vb);
                    }
                    AtomicBoolean needAdd = new AtomicBoolean(true);
                    units.forEach(each -> {
                        if (each.toString().equals(stmt.toString())) {
                            needAdd.set(false);
                        }
                    });
                    if (needAdd.get()) {
                        if (stmt.toString().contains("@this")) {
                            if (!units.isEmpty()) {
                                units.insertBefore(stmt, units.getFirst());
                            } else {
                                units.add(stmt);
                            }
                        } else {
                            units.add(stmt);
                        }
                    }
                }
            }

            //Add locals & units [in unit]
            if (caseModel.unit.toString().contains("$r8.<android.content.Intent: android.content.Intent setClassName(android.content.Context,java.lang.String)>($r1, \"com.google.android.gms.ads.AdActivity\")")) {
                System.out.println("jj");
            }
            caseModel.unit.getUseAndDefBoxes().forEach(ub -> {
                if ((ub.getValue() instanceof JimpleLocal) && !(ub.getValue() instanceof Constant)) {
                    if (!body.getLocals().contains((JimpleLocal) ub.getValue())) {
                        body.getLocals().add((JimpleLocal) ub.getValue());
                        //if ub.getValue() is a parameter of the unit, then we cast it to a NULL Constant
                        String params = Rgex.getSubUtilSimple(caseModel.unit.toString(), "(>\\(.*\\))");

                        if (params.contains(ub.getValue().toString())) {
                            if (ApplicationClassFilter.isClassInSystemPackage(ub.getValue().getType().toString())) {
//                                units.add(Jimple.v().newAssignStmt(ub.getValue(),
//                                        Jimple.v().newStaticInvokeExpr(
//                                                Scene.v().getMethod("<org.easymock.EasyMock: java.lang.Object createMock(java.lang.Class)>").makeRef(),
//                                                new ImmediateBox(ClassConstant.v(ub.getValue().getType().toString().replaceAll("\\.", "\\/"))).getValue()
//                                        )
//                                        //Scene.v().getSootClass("android.content.Intent").getMethods().get(0)
//                                ));

                                //ConstructorMethod to replace easymock
                                SootMethod paramSootMethod = getConstructorMethod(Scene.v().getSootClass(ub.getValue().getType().toString()));
                                if (paramSootMethod.getParameterCount() == 0) {
                                    if (paramSootMethod.isStatic()) {
                                        units.add(Jimple.v().newAssignStmt((JimpleLocal) ub.getValue(), Jimple.v().newStaticInvokeExpr(paramSootMethod.makeRef())));
                                    } else {
                                        units.add(Jimple.v().newAssignStmt((JimpleLocal) ub.getValue(), Jimple.v().newNewExpr(RefType.v(ub.getValue().getType().toString()))));
                                        units.add(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((JimpleLocal) ub.getValue(), paramSootMethod.makeRef())));
                                    }
                                } else if (paramSootMethod.getParameterCount() > 0) {
                                    List<Value> paramList = new ArrayList<>();
                                    boolean isPossible2MockPrams = true;
                                    for (int paramNo = 0; paramNo < paramSootMethod.getParameterCount(); paramNo++) {
                                        Type currentParam = paramSootMethod.getParameterType(paramNo);
                                        if (currentParam instanceof PrimType) {
                                            Local currentParamLocal = Jimple.v().newLocal("param" + paramNo, currentParam);
                                            Value currentParamValue = newPrimType((PrimType) currentParam);
                                            body.getLocals().add(currentParamLocal);
                                            units.add(Jimple.v().newAssignStmt(currentParamLocal, currentParamValue));
                                            paramList.add(currentParamValue);
                                        } else if (currentParam instanceof ArrayType && currentParam.getArrayType().baseType instanceof PrimType) {
                                            Local currentParamLocal = Jimple.v().newLocal("param" + paramNo, currentParam);
                                            Value currentParamValue = newNewArrayExpr((PrimType) currentParam.getArrayType().baseType);
                                            body.getLocals().add(currentParamLocal);
                                            units.add(Jimple.v().newAssignStmt(currentParamLocal, currentParamValue));
                                            paramList.add(currentParamValue);
                                        } else if (ApplicationClassFilter.isJavaBasicType(currentParam.toString())) {
                                            Local currentParamLocal = Jimple.v().newLocal("param" + paramNo, currentParam);
                                            Value currentParamValue = newPrimType2(currentParam.toString());
                                            body.getLocals().add(currentParamLocal);
                                            units.add(Jimple.v().newAssignStmt(currentParamLocal, currentParamValue));
                                            paramList.add(currentParamValue);
                                        } else if (currentParam instanceof ArrayType
                                                && currentParam.getArrayType().baseType instanceof RefType
                                                && ApplicationClassFilter.isJavaBasicType(currentParam.getArrayType().baseType.toString())) {
                                            Local currentParamLocal = Jimple.v().newLocal("param" + paramNo, currentParam);
                                            newNewArrayExpr2(currentParam.toString(), units, currentParamLocal, body, currentParamLocal.getName());
                                            body.getLocals().add(currentParamLocal);
                                            paramList.add(currentParamLocal);
                                        } else if (ApplicationClassFilter.isClassInSystemPackage(currentParam.toString())) {
                                            Local currentParamLocal = Jimple.v().newLocal("param" + paramNo, currentParam);
                                            body.getLocals().add(currentParamLocal);

                                            SootMethod sm = getConstructorMethod(Scene.v().getSootClass(currentParam.toString()));
                                            if (sm.isStatic()) {
                                                units.add(Jimple.v().newAssignStmt(currentParamLocal, Jimple.v().newStaticInvokeExpr(sm.makeRef())));
                                            } else {
                                                units.add(Jimple.v().newAssignStmt(currentParamLocal, Jimple.v().newNewExpr(RefType.v(currentParam.toString()))));
                                                units.add(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(currentParamLocal, sm.makeRef())));
                                            }

                                            paramList.add(currentParamLocal);
                                        } else {
                                            isPossible2MockPrams = false;
                                        }
                                    }
                                    if (isPossible2MockPrams) {
                                        if (paramSootMethod.isStatic()) {
                                            units.add(Jimple.v().newAssignStmt((JimpleLocal) ub.getValue(), Jimple.v().newStaticInvokeExpr(paramSootMethod.makeRef(), paramList)));
                                        } else {
                                            units.add(Jimple.v().newAssignStmt((JimpleLocal) ub.getValue(), Jimple.v().newNewExpr(RefType.v(ub.getValue().getType().toString()))));
                                            units.add(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((JimpleLocal) ub.getValue(), paramSootMethod.makeRef(), paramList)));
                                        }
                                    } else {
                                        units.add(Jimple.v().newAssignStmt(ub.getValue(),
                                                Jimple.v().newStaticInvokeExpr(
                                                        Scene.v().getMethod("<org.easymock.EasyMock: java.lang.Object createMock(java.lang.Class)>").makeRef(),
                                                        new ImmediateBox(ClassConstant.v(ub.getValue().getType().toString().replaceAll("\\.", "\\/"))).getValue()
                                                )
                                        ));
                                    }
                                }
                            } else if (ub.getValue().getType() instanceof PrimType) {

                                Value defaultValue4PrimType = newPrimType((PrimType) ub.getValue().getType());
                                if (null != defaultValue4PrimType) {
                                    units.add(Jimple.v().newAssignStmt(ub.getValue(), defaultValue4PrimType));
                                }
                            } else if (ub.getValue().getType() instanceof ArrayType && ub.getValue().getType().getArrayType().baseType instanceof PrimType) {
                                Value defaultArray = newNewArrayExpr((PrimType) ub.getValue().getType().getArrayType().baseType);
                                if (null != defaultArray) {
                                    units.add(Jimple.v().newAssignStmt(ub.getValue(), defaultArray));
                                }
                            } else if (ApplicationClassFilter.isJavaBasicType(ub.getValue().getType().toString())) {

                                newPrimType(ub.getValue().getType().getArrayType().baseType.toString(), units, ub, body);

                            } else if (ub.getValue().getType() instanceof ArrayType
                                    && ub.getValue().getType().getArrayType().baseType instanceof RefType
                                    && ApplicationClassFilter.isJavaBasicType(ub.getValue().getType().getArrayType().baseType.toString())) {

                                newNewArrayExpr(ub.getValue().getType().getArrayType().baseType.toString(), units, ub, body);

                            } else {
                                units.add(Jimple.v().newAssignStmt(ub.getValue(), NullConstant.v()));
                            }
                        }
                    }
                }
            });

            if (caseModel.unit.toString().contains("$r5 = virtualinvoke $r0.<android.content.Context: java.io.File getDir(java.lang.String,int)>(\"dex\", 0)")) {
                System.out.println("hh");
            }

            if (caseModel.unit instanceof JAssignStmt) {
                units.add(caseModel.unit);
                Type returnType = ((JAssignStmt) caseModel.unit).getLeftOp().getType();
                if (returnType instanceof PrimType) {
                    RefType primeType = prime2RefType((PrimType) returnType);
                    Local returnPrimeRef = addReturnPrimeType(body, primeType);
                    SootClass primeRefTypeClass = getPrimeRefTypeClass((PrimType) returnType);
                    units.add(Jimple.v().newAssignStmt(returnPrimeRef, Jimple.v().newStaticInvokeExpr(Scene.v().makeMethodRef(primeRefTypeClass, "valueOf", Arrays.asList((PrimType) returnType), primeType, true), ((JAssignStmt) caseModel.unit).getLeftOp())));
                    units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newStaticInvokeExpr(Scene.v().makeMethodRef(systemOutMethod.getDeclaringClass(), systemOutMethod.getName(), systemOutMethod.getParameterTypes(), systemOutMethod.getReturnType(), true), returnPrimeRef)).getValue()));
                }else{
                    units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newStaticInvokeExpr(Scene.v().makeMethodRef(systemOutMethod.getDeclaringClass(), systemOutMethod.getName(), systemOutMethod.getParameterTypes(), systemOutMethod.getReturnType(), true), ((JAssignStmt) caseModel.unit).getLeftOp())).getValue()));
                }
            } else if (caseModel.unit instanceof JInvokeStmt) {
                Type returnType = ((JInvokeStmt) caseModel.unit).getInvokeExpr().getMethodRef().getReturnType();
                if (!returnType.toString().equals(VoidType.v().toString())) {
                    Local returnRef = addReturnRef(body, returnType);
                    if (returnRef.getType() instanceof PrimType) {
                        units.add(Jimple.v().newAssignStmt(returnRef, ((JInvokeStmt) caseModel.unit).getInvokeExpr()));
                        RefType primeType = prime2RefType((PrimType) returnRef.getType());
                        Local returnPrimeRef = addReturnPrimeType(body, primeType);
                        SootClass primeRefTypeClass = getPrimeRefTypeClass((PrimType) returnRef.getType());
                        units.add(Jimple.v().newAssignStmt(returnPrimeRef, Jimple.v().newStaticInvokeExpr(Scene.v().makeMethodRef(primeRefTypeClass, "valueOf", Arrays.asList((PrimType) returnRef.getType()), primeType, true), returnRef)));
                        units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newStaticInvokeExpr(Scene.v().makeMethodRef(systemOutMethod.getDeclaringClass(), systemOutMethod.getName(), systemOutMethod.getParameterTypes(), systemOutMethod.getReturnType(), true), returnPrimeRef)).getValue()));
                    } else {
                        units.add(Jimple.v().newAssignStmt(returnRef, ((JInvokeStmt) caseModel.unit).getInvokeExpr()));
                        units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newStaticInvokeExpr(Scene.v().makeMethodRef(systemOutMethod.getDeclaringClass(), systemOutMethod.getName(), systemOutMethod.getParameterTypes(), systemOutMethod.getReturnType(), true), returnRef)).getValue()));
                    }
                } else {
                    units.add(caseModel.unit);
                    units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newStaticInvokeExpr(Scene.v().makeMethodRef(systemOutMethod.getDeclaringClass(), systemOutMethod.getName(), systemOutMethod.getParameterTypes(), systemOutMethod.getReturnType(), true), StringConstant.v("void"))).getValue()));
                }
            } else {
                System.out.println("================::::" + caseModel.unit);
            }

            //units.add(caseModel.unit);
            units.add(Jimple.v().newReturnVoidStmt());

        }

        //output xxxTest.class
        String fileName = SourceLocator.v().getFileNameFor(sClass, Options.output_format_class);
        OutputStream streamOut = new JasminOutputStream(
                new FileOutputStream(fileName));
        PrintWriter writerOut = new PrintWriter(
                new OutputStreamWriter(streamOut));
        JasminClass jasminClass = new JasminClass(sClass);
        jasminClass.print(writerOut);
        writerOut.flush();
        streamOut.close();

        //output API invocations in minContext for simplicity analysis
        String apiSequencesFile = GlobalRef.SOOTOUTPUT + "/" + caseModel.sootClassName + ".txt";
        for (
                Unit unit : testMethod.getActiveBody().

                getUnits()) {
            if (unit.toString().contains("thisObject") || unit.toString().contains("thisActivity") || unit.toString().contains("@this") || unit.toString().contains("Test: void out(java.lang.Object)") || unit.toString().equals("return")) {
                continue;
            }
            PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(apiSequencesFile, true)));
            String methodSig = "";
            if (unit.toString().contains("<") && unit.toString().contains(">")) {
                methodSig = Rgex.getSubUtilSimple(unit.toString(), "(<.*>)");
            }
            if (StringUtils.isNotBlank(methodSig)) {
                out.println(methodSig);
            }
            out.close();
        }

        return true;
    }

    private static SootMethod processOutMethod(SootClass sClass) {
        SootMethod systemOutMethod = new SootMethod("out", Arrays.asList(RefType.v("java.lang.Object")), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        sClass.addMethod(systemOutMethod);
        // create empty body
        JimpleBody body = Jimple.v().newBody(systemOutMethod);
        systemOutMethod.setActiveBody(body);

        Chain units = body.getUnits();

        Local r0 = Jimple.v().newLocal("r0", RefType.v("java.lang.Object"));
        Local r1 = Jimple.v().newLocal("r1", RefType.v("android.os.Bundle"));
        Local r2 = Jimple.v().newLocal("r2", RefType.v("java.lang.StringBuilder"));
        Local r3 = Jimple.v().newLocal("r3", RefType.v("java.lang.String"));
        Local r4 = Jimple.v().newLocal("r4", RefType.v("android.app.Instrumentation"));
        body.getLocals().add(r0);
        body.getLocals().add(r1);
        body.getLocals().add(r2);
        body.getLocals().add(r3);
        body.getLocals().add(r4);

        units.add(Jimple.v().newIdentityStmt(r0, Jimple.v().newIdentityRefBox(new ParameterRef(RefType.v("java.lang.Object"), 0)).getValue()));
        units.add(Jimple.v().newAssignStmt(r1, Jimple.v().newNewExpr(RefType.v("android.os.Bundle"))));

        SootClass bundleSootClass = Scene.v().getSootClass("android.os.Bundle");
        SootMethod bundleSootMethod = bundleSootClass.getMethod("<init>", Arrays.asList(), VoidType.v());
        units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newSpecialInvokeExpr(r1, Scene.v().makeMethodRef(bundleSootClass, "<init>", bundleSootMethod.getParameterTypes(), bundleSootMethod.getReturnType(), false))).getValue()));

        units.add(Jimple.v().newAssignStmt(r2, Jimple.v().newNewExpr(RefType.v("java.lang.StringBuilder"))));

        SootClass strBuilderSootClass = Scene.v().getSootClass("java.lang.StringBuilder");
        SootMethod strBuilderSootMethod = strBuilderSootClass.getMethod("<init>", Arrays.asList(), VoidType.v());
        units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newSpecialInvokeExpr(r2, Scene.v().makeMethodRef(strBuilderSootClass, "<init>", strBuilderSootMethod.getParameterTypes(), strBuilderSootMethod.getReturnType(), false))).getValue()));
        units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newVirtualInvokeExpr(r2, Scene.v().makeMethodRef(strBuilderSootClass, "append", Arrays.asList(RefType.v("java.lang.String")), RefType.v("java.lang.StringBuilder"), false), new ImmediateBox(StringConstant.v("[result]")).getValue())).getValue()));
        units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newVirtualInvokeExpr(r2, Scene.v().makeMethodRef(strBuilderSootClass, "append", Arrays.asList(RefType.v("java.lang.String")), RefType.v("java.lang.StringBuilder"), false), new ImmediateBox(r0).getValue())).getValue()));
        units.add(Jimple.v().newAssignStmt(r3, Jimple.v().newVirtualInvokeExpr(r2, Scene.v().makeMethodRef(strBuilderSootClass, "toString", Arrays.asList(), RefType.v("java.lang.String"), false))));
        units.add(Jimple.v().newInvokeStmt(Jimple.v().newInvokeExprBox(Jimple.v().newVirtualInvokeExpr(r1, Scene.v().makeMethodRef(bundleSootClass, "putString", Arrays.asList(RefType.v("java.lang.String"), RefType.v("java.lang.String")), VoidType.v(), false), new ImmediateBox(StringConstant.v("stream")).getValue(), new ImmediateBox(r3).getValue())).getValue()));

        SootClass instrumentationRegistrySootClass = Scene.v().getSootClass("androidx.test.platform.app.InstrumentationRegistry");
        units.add(Jimple.v().newAssignStmt(r4, Jimple.v().newStaticInvokeExpr(Scene.v().makeMethodRef(instrumentationRegistrySootClass, "getInstrumentation", Arrays.asList(), RefType.v("android.app.Instrumentation"), true))));

        SootClass instrumentationSootClass = Scene.v().getSootClass("android.app.Instrumentation");
        units.add(Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(r4, Scene.v().makeMethodRef(instrumentationSootClass, "sendStatus", Arrays.asList(IntType.v(), RefType.v("android.os.Bundle")), VoidType.v(), false), IntConstant.v(0), r1)));
        units.add(Jimple.v().newReturnVoidStmt());

        return systemOutMethod;
    }

    private static Local addSysOutputRef(Body body) {
        Local sysOutputRef = Jimple.v().newLocal("sysOutputRef", RefType.v("java.io.PrintStream"));
        body.getLocals().add(sysOutputRef);
        return sysOutputRef;
    }

    private static Local addReturnRef(Body body, Type returnType) {
        Local returnRef = Jimple.v().newLocal("returnRef", returnType);
        body.getLocals().add(returnRef);
        return returnRef;
    }

    private static Local addReturnPrimeType(Body body, Type returnType) {
        Local returnRef = Jimple.v().newLocal("returnPrimeRef", returnType);
        body.getLocals().add(returnRef);
        return returnRef;
    }

    private static SootMethod getConstructorMethod(SootClass sootClass) {
        if (CollectionUtils.isEmpty(sootClass.getMethods())) {
            return null;
        }

        if(sootClass.getName().equals("android.content.Context")){
            return Scene.v().getMethod("<androidx.test.InstrumentationRegistry: android.content.Context getTargetContext()>");
        }

        int minParameterCount = 100;
        for (SootMethod sm : sootClass.getMethods()) {
            if ((sm.getName().contains("<clinit>") || sm.getName().contains("<init>")) && sm.getParameterCount() < minParameterCount && !sm.isStatic() && sm.isPublic() && !sootClass.isAbstract()) {
                minParameterCount = sm.getParameterCount();
            }
        }

        for (SootMethod sm : sootClass.getMethods()) {
            if (sm.getParameterCount() == minParameterCount) {
                return sm;
            }
        }
        return null;
    }

    private static NewArrayExpr newNewArrayExpr(String className, Chain units, ValueBox ub, JimpleBody body) {
        if (className.startsWith("java.lang.String")) {
            Local arrayParameter = Jimple.v().newLocal("arrayParameter", ArrayType.v(RefType.v("java.lang.String"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.String"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), StringConstant.v("android"));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(ub.getValue(), arrayParameter));

        } else if (className.startsWith("java.lang.Boolean")) {
            Local arrayParameter = Jimple.v().newLocal("arrayParameter", ArrayType.v(RefType.v("java.lang.Boolean"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Boolean"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), DIntConstant.v(1, BooleanType.v()));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(ub.getValue(), arrayParameter));
        } else if (className.startsWith("java.lang.Byte")) {

            Local arrayParameter = Jimple.v().newLocal("arrayParameter", ArrayType.v(RefType.v("java.lang.Byte"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Byte"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), DIntConstant.v(1, ByteType.v()));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(ub.getValue(), arrayParameter));

        } else if (className.startsWith("java.lang.Character")) {

            Local arrayParameter = Jimple.v().newLocal("arrayParameter", ArrayType.v(RefType.v("java.lang.Character"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Character"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), DIntConstant.v(1, CharType.v()));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(ub.getValue(), arrayParameter));

        } else if (className.startsWith("java.lang.Double")) {

            Local arrayParameter = Jimple.v().newLocal("arrayParameter", ArrayType.v(RefType.v("java.lang.Double"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Double"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), DoubleConstant.v(1.0));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(ub.getValue(), arrayParameter));

        } else if (className.startsWith("java.lang.Float")) {

            Local arrayParameter = Jimple.v().newLocal("arrayParameter", ArrayType.v(RefType.v("java.lang.Float"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Float"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), FloatConstant.v((float) 1.0));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(ub.getValue(), arrayParameter));

        } else if (className.startsWith("java.lang.Integer")) {

            Local arrayParameter = Jimple.v().newLocal("arrayParameter", ArrayType.v(RefType.v("java.lang.Integer"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Integer"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), IntConstant.v(1));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(ub.getValue(), arrayParameter));

        } else if (className.startsWith("java.lang.Long")) {

            Local arrayParameter = Jimple.v().newLocal("arrayParameter", ArrayType.v(RefType.v("java.lang.Long"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Long"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), LongConstant.v(1));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(ub.getValue(), arrayParameter));

        } else if (className.startsWith("java.lang.Short")) {
            Local arrayParameter = Jimple.v().newLocal("arrayParameter", ArrayType.v(RefType.v("java.lang.Short"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Short"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), IntConstant.v(1));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(ub.getValue(), arrayParameter));

        }
        return null;
    }

    private static NewArrayExpr newNewArrayExpr2(String className, Chain units, Local currentParamLocal, JimpleBody body, String paramName) {
        if (className.startsWith("java.lang.String")) {
            Local arrayParameter = Jimple.v().newLocal(paramName, ArrayType.v(RefType.v("java.lang.String"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.String"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), StringConstant.v("android"));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(currentParamLocal, arrayParameter));

        } else if (className.startsWith("java.lang.Boolean")) {
            Local arrayParameter = Jimple.v().newLocal(paramName, ArrayType.v(RefType.v("java.lang.Boolean"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Boolean"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), DIntConstant.v(1, BooleanType.v()));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(currentParamLocal, arrayParameter));
        } else if (className.startsWith("java.lang.Byte")) {

            Local arrayParameter = Jimple.v().newLocal(paramName, ArrayType.v(RefType.v("java.lang.Byte"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Byte"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), DIntConstant.v(1, ByteType.v()));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(currentParamLocal, arrayParameter));

        } else if (className.startsWith("java.lang.Character")) {

            Local arrayParameter = Jimple.v().newLocal(paramName, ArrayType.v(RefType.v("java.lang.Character"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Character"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), DIntConstant.v(1, CharType.v()));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(currentParamLocal, arrayParameter));

        } else if (className.startsWith("java.lang.Double")) {

            Local arrayParameter = Jimple.v().newLocal(paramName, ArrayType.v(RefType.v("java.lang.Double"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Double"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), DoubleConstant.v(1.0));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(currentParamLocal, arrayParameter));

        } else if (className.startsWith("java.lang.Float")) {

            Local arrayParameter = Jimple.v().newLocal(paramName, ArrayType.v(RefType.v("java.lang.Float"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Float"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), FloatConstant.v((float) 1.0));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(currentParamLocal, arrayParameter));

        } else if (className.startsWith("java.lang.Integer")) {

            Local arrayParameter = Jimple.v().newLocal(paramName, ArrayType.v(RefType.v("java.lang.Integer"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Integer"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), IntConstant.v(1));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(currentParamLocal, arrayParameter));

        } else if (className.startsWith("java.lang.Long")) {

            Local arrayParameter = Jimple.v().newLocal(paramName, ArrayType.v(RefType.v("java.lang.Long"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Long"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), LongConstant.v(1));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(currentParamLocal, arrayParameter));

        } else if (className.startsWith("java.lang.Short")) {
            Local arrayParameter = Jimple.v().newLocal(paramName, ArrayType.v(RefType.v("java.lang.Short"), 1));
            body.getLocals().add(arrayParameter);

            AssignStmt assignStmt1 = Jimple.v().newAssignStmt(arrayParameter, Jimple.v().newNewArrayExpr(RefType.v("java.lang.Short"), IntConstant.v(1)));
            units.add(assignStmt1);

            AssignStmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newArrayRef(arrayParameter, IntConstant.v(0)), IntConstant.v(1));
            units.add(assignStmt);
            units.add(Jimple.v().newAssignStmt(currentParamLocal, arrayParameter));

        }
        return null;
    }

    private static NewArrayExpr newNewArrayExpr(PrimType primType) {
        if (primType instanceof BooleanType) {
            return Jimple.v().newNewArrayExpr(BooleanType.v(), DIntConstant.v(1, BooleanType.v()));
        } else if (primType instanceof ByteType) {
            return Jimple.v().newNewArrayExpr(ByteType.v(), DIntConstant.v(1, ByteType.v()));
        } else if (primType instanceof CharType) {
            return Jimple.v().newNewArrayExpr(CharType.v(), DIntConstant.v(1, CharType.v()));
        } else if (primType instanceof DoubleType) {
            return Jimple.v().newNewArrayExpr(DoubleType.v(), DoubleConstant.v(1.0));
        } else if (primType instanceof FloatType) {
            return Jimple.v().newNewArrayExpr(FloatType.v(), FloatConstant.v((float) 1.0));
        } else if (primType instanceof IntType) {
            return Jimple.v().newNewArrayExpr(IntType.v(), IntConstant.v(1));
        } else if (primType instanceof Integer127Type) {
            return Jimple.v().newNewArrayExpr(Integer127Type.v(), IntConstant.v(1));
        } else if (primType instanceof Integer1Type) {
            return Jimple.v().newNewArrayExpr(Integer1Type.v(), IntConstant.v(1));
        } else if (primType instanceof Integer32767Type) {
            return Jimple.v().newNewArrayExpr(Integer32767Type.v(), IntConstant.v(1));
        } else if (primType instanceof LongType) {
            return Jimple.v().newNewArrayExpr(LongType.v(), LongConstant.v(1));
        } else if (primType instanceof ShortType) {
            return Jimple.v().newNewArrayExpr(ShortType.v(), IntConstant.v(1));
        }
        return null;
    }

    private static Value newPrimType(String className, Chain units, ValueBox ub, JimpleBody body) {
        if (className.startsWith("java.lang.String")) {

            units.add(Jimple.v().newAssignStmt(ub.getValue(), StringConstant.v("android")));

        } else if (className.startsWith("java.lang.Boolean")) {

            units.add(Jimple.v().newAssignStmt(ub.getValue(), DIntConstant.v(1, BooleanType.v())));

        } else if (className.startsWith("java.lang.Byte")) {

            units.add(Jimple.v().newAssignStmt(ub.getValue(), DIntConstant.v(1, ByteType.v())));

        } else if (className.startsWith("java.lang.Character")) {

            units.add(Jimple.v().newAssignStmt(ub.getValue(), DIntConstant.v(1, CharType.v())));

        } else if (className.startsWith("java.lang.Double")) {

            units.add(Jimple.v().newAssignStmt(ub.getValue(), DoubleConstant.v(1.0)));

        } else if (className.startsWith("java.lang.Float")) {

            units.add(Jimple.v().newAssignStmt(ub.getValue(), FloatConstant.v((float) 1.0)));

        } else if (className.startsWith("java.lang.Integer")) {

            units.add(Jimple.v().newAssignStmt(ub.getValue(), IntConstant.v(1)));

        } else if (className.startsWith("java.lang.Long")) {

            units.add(Jimple.v().newAssignStmt(ub.getValue(), LongConstant.v(1)));

        } else if (className.startsWith("java.lang.Short")) {

            units.add(Jimple.v().newAssignStmt(ub.getValue(), IntConstant.v(1)));
        }
        return null;
    }

    private static Value newPrimType2(String className) {
        if (className.startsWith("java.lang.String")) {
            return StringConstant.v("android");
        } else if (className.startsWith("java.lang.Boolean")) {
            return DIntConstant.v(1, BooleanType.v());
        } else if (className.startsWith("java.lang.Byte")) {
            return DIntConstant.v(1, ByteType.v());
        } else if (className.startsWith("java.lang.Character")) {
            return DIntConstant.v(1, CharType.v());
        } else if (className.startsWith("java.lang.Double")) {
            return DoubleConstant.v(1.0);
        } else if (className.startsWith("java.lang.Float")) {
            return FloatConstant.v((float) 1.0);
        } else if (className.startsWith("java.lang.Integer")) {
            return IntConstant.v(1);
        } else if (className.startsWith("java.lang.Long")) {
            return LongConstant.v(1);
        } else if (className.startsWith("java.lang.Short")) {
            return IntConstant.v(1);
        }
        return null;
    }

    private static RefType prime2RefType(PrimType primType) {
        if (primType instanceof LongType) {
            return RefType.v("java.lang.Long");
        } else if (primType instanceof BooleanType) {
            return RefType.v("java.lang.Boolean");
        } else if (primType instanceof ByteType) {
            return RefType.v("java.lang.Byte");
        } else if (primType instanceof CharType) {
            return RefType.v("java.lang.Character");
        } else if (primType instanceof DoubleType) {
            return RefType.v("java.lang.Double");
        } else if (primType instanceof FloatType) {
            return RefType.v("java.lang.Float");
        } else if (primType instanceof IntType) {
            return RefType.v("java.lang.Integer");
        } else if (primType instanceof ShortType) {
            return RefType.v("java.lang.Short");
        }
        return null;
    }

    private static SootClass getPrimeRefTypeClass(PrimType primType) {
        if (primType instanceof LongType) {
            return Scene.v().getSootClass("java.lang.Long");
        } else if (primType instanceof BooleanType) {
            return Scene.v().getSootClass("java.lang.Boolean");
        } else if (primType instanceof ByteType) {
            return Scene.v().getSootClass("java.lang.Byte");
        } else if (primType instanceof CharType) {
            return Scene.v().getSootClass("java.lang.Character");
        } else if (primType instanceof DoubleType) {
            return Scene.v().getSootClass("java.lang.Double");
        } else if (primType instanceof FloatType) {
            return Scene.v().getSootClass("java.lang.Float");
        } else if (primType instanceof IntType) {
            return Scene.v().getSootClass("java.lang.Integer");
        } else if (primType instanceof ShortType) {
            return Scene.v().getSootClass("java.lang.Short");
        }
        return null;
    }

    private static Value newPrimType(PrimType primType) {
        if (primType instanceof BooleanType) {
            return DIntConstant.v(1, BooleanType.v());
        } else if (primType instanceof ByteType) {
            return DIntConstant.v(1, ByteType.v());
        } else if (primType instanceof CharType) {
            return DIntConstant.v(32, CharType.v());
        } else if (primType instanceof DoubleType) {
            return DoubleConstant.v(1.0);
        } else if (primType instanceof FloatType) {
            return FloatConstant.v((float) 1.0);
        } else if (primType instanceof IntType) {
            return IntConstant.v(1);
        } else if (primType instanceof Integer127Type) {
            return IntConstant.v(1);
        } else if (primType instanceof Integer1Type) {
            return IntConstant.v(1);
        } else if (primType instanceof Integer32767Type) {
            return IntConstant.v(1);
        } else if (primType instanceof LongType) {
            return LongConstant.v(1);
        } else if (primType instanceof ShortType) {
            return IntConstant.v(1);
        }
        return null;
    }

    private static void duplicate(APITestCaseModel caseModel) {
        if (CollectionUtils.isEmpty(caseModel.baseBoxStmts)) {
            return;
        }

        List<Object> duplicatedList = new ArrayList<>();
        Set<String> tmp = new HashSet<>();

        HashMap<LocalModel, JimpleLocal> availiableLocals = new HashMap<>();

        for (int i = caseModel.baseBoxStmts.size() - 1; i >= 0; i--) {
            String currentStmt = caseModel.baseBoxStmts.get(i).toString();
            if (tmp.contains(currentStmt)) {

            } else {
                tmp.add(currentStmt);
                if (caseModel.baseBoxStmts.get(i) instanceof JAssignStmt && ((JAssignStmt) caseModel.baseBoxStmts.get(i)).getDefBoxes().size() > 0) {
                    LocalModel localModel = new LocalModel();
                    JimpleLocal local = (JimpleLocal) ((JAssignStmt) caseModel.baseBoxStmts.get(i)).getDefBoxes().get(0).getValue();
                    localModel.name = local.getName();
                    localModel.number = local.getNumber();
                    availiableLocals.put(localModel, local);
                }
                if (caseModel.baseBoxStmts.get(i) instanceof JIdentityStmt && ((JIdentityStmt) caseModel.baseBoxStmts.get(i)).getDefBoxes().size() > 0) {
                    LocalModel localModel = new LocalModel();
                    JimpleLocal local = (JimpleLocal) ((JIdentityStmt) caseModel.baseBoxStmts.get(i)).getDefBoxes().get(0).getValue();
                    localModel.name = local.getName();
                    localModel.number = local.getNumber();
                    availiableLocals.put(localModel, local);
                }

                if (caseModel.baseBoxStmts.get(i) instanceof JAssignStmt && ((JAssignStmt) caseModel.baseBoxStmts.get(i)).getRightOp().getUseBoxes().size() > 0) {
                    for (ValueBox vb : ((JAssignStmt) caseModel.baseBoxStmts.get(i)).getRightOp().getUseBoxes()) {
                        if (vb.getValue() instanceof JimpleLocal) {
                            LocalModel lm = new LocalModel();
                            lm.name = ((JimpleLocal) vb.getValue()).getName();
                            lm.number = ((JimpleLocal) vb.getValue()).getNumber();

                            if (!availiableLocals.containsKey(lm)) {
                                for (LocalModel m : availiableLocals.keySet()) {
                                    if (m.isSameByNameNotNum(lm)) {
                                        vb.setValue(availiableLocals.get(m));
                                    }
                                }
                            }
                        }
                    }
                }
                duplicatedList.add(0, caseModel.baseBoxStmts.get(i));
            }
        }
        caseModel.baseBoxStmts = duplicatedList;

    }

    private static void replaceAPKClasses(APITestCaseModel caseModel) {
        if (CollectionUtils.isEmpty(caseModel.baseBoxStmts)) {
            return;
        }
        for (int i = caseModel.baseBoxStmts.size() - 1; i >= 0; i--) {
            if (!(caseModel.baseBoxStmts.get(i) instanceof Stmt)) {
                continue;
            }
            Stmt stmt = (Stmt) caseModel.baseBoxStmts.get(i);

            if (!stmt.containsInvokeExpr() || stmt.getInvokeExpr().getArgs().size() <= 0) {
                continue;
            }

            for (int index = 0; index < stmt.getInvokeExpr().getArgs().size(); index++) {
                Value arg = stmt.getInvokeExpr().getArg(index);
                if (arg instanceof ClassConstant
                        && Scene.v().getSootClass(((ClassConstant) arg).toSootType().toString()).hasSuperclass()
                        && Scene.v().getSootClass(((ClassConstant) arg).toSootType().toString()).getSuperclass().getName().equals("android.app.Service")
                ) {
                    stmt.getInvokeExpr().setArg(index, ClassConstant.fromType(Scene.v().getSootClass("com.example.android.testing.unittesting.basicunitandroidtest.MyService").getType()));
                } else if (arg instanceof ClassConstant
                        && !ApplicationClassFilter.isAndroidSystemPackage(((ClassConstant) arg).toSootType().toString())
                        && Scene.v().getSootClass(((ClassConstant) arg).toSootType().toString()).hasSuperclass()
                        && ApplicationClassFilter.isAndroidSystemPackage(Scene.v().getSootClass(((ClassConstant) arg).toSootType().toString()).getSuperclass().getName())
                ) {
                    stmt.getInvokeExpr().setArg(index, ClassConstant.fromType(Scene.v().getSootClass(Scene.v().getSootClass(((ClassConstant) arg).toSootType().toString()).getSuperclass().getName()).getType()));
                }
            }
        }
    }

    private static void processFiledLocal(JimpleBody body, ValueBox vb) {
        if (vb.getValue() instanceof JInstanceFieldRef && !body.getLocals().contains(vb.getValue().getUseBoxes().get(0).getValue())) {

            Local fieldLocal = null;
            for (Local l : body.getLocals()) {
                if (l.getName().equals(vb.getValue().getUseBoxes().get(0).getValue().toString())) {
                    fieldLocal = l;
                    break;
                }
            }

            if (fieldLocal != null) {
                vb.setValue(fieldLocal);
            }
        }
    }

    private static void processStaticArchitecture(APITestCaseModel caseModel, SootClass sClass, SootMethod testMethod) {
        if (caseModel.apiType != APIType.STATIC_INVOKE) {
            return;
        }

        SootMethod staticTestMethod = new SootMethod("staticTest", Arrays.asList(), VoidType.v(), Modifier.PUBLIC);
        AnnotationGenerator.v().annotate(staticTestMethod, Test.class, new ArrayList<>());
        staticTestMethod.addException(Scene.v().getSootClass("java.lang.Exception"));

        sClass.addMethod(staticTestMethod);

        // create empty body
        JimpleBody body = Jimple.v().newBody(staticTestMethod);

        staticTestMethod.setActiveBody(body);
        Chain units = body.getUnits();

        Local thisLocal = Jimple.v().newLocal("thisObject", RefType.v(sClass.getName()));
        body.getLocals().add(thisLocal);
        units.add(Jimple.v().newIdentityStmt(thisLocal, Jimple.v().newIdentityRefBox(new ThisRef(RefType.v(sClass.getName()))).getValue()));
        units.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(Scene.v().makeMethodRef(testMethod.getDeclaringClass(), testMethod.getName(), testMethod.getParameterTypes(), testMethod.getReturnType(), testMethod.isStatic()))));
        units.add(Jimple.v().newReturnVoidStmt());
    }

    private static void processContextAndParameter(APITestCaseModel caseModel, JimpleBody body, Chain units) {
        if (CollectionUtils.isEmpty(caseModel.baseBoxStmts)) {
            return;
        }
        for (int i = caseModel.baseBoxStmts.size() - 1; i >= 0; i--) {
            if (!(caseModel.baseBoxStmts.get(i) instanceof Stmt)) {
                continue;
            }
            Stmt stmt = (Stmt) caseModel.baseBoxStmts.get(i);
            if (stmt instanceof JIdentityStmt && ((JIdentityStmt) stmt).getRightOpBox().getValue() instanceof ParameterRef && ((JIdentityStmt) stmt).getRightOpBox().getValue().getType().toString().equals("android.content.Context")) {
                caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(((JIdentityStmt) stmt).getLeftOp(), Jimple.v().newStaticInvokeExpr(
                        Scene.v().getMethod("<androidx.test.InstrumentationRegistry: android.content.Context getTargetContext()>").makeRef()
                )));
            } else if (stmt instanceof JAssignStmt && ((JAssignStmt) stmt).getRightOpBox().getValue().getType().toString().equals("android.content.Context") && ((JAssignStmt) stmt).getRightOpBox().getValue().toString().contains("getContext")) {
                caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(((JAssignStmt) stmt).getLeftOp(), Jimple.v().newStaticInvokeExpr(
                        Scene.v().getMethod("<androidx.test.InstrumentationRegistry: android.content.Context getTargetContext()>").makeRef()
                )));
            }

            //example:$r1 = virtualinvoke $r0.<com.comment.one.service.DmService: android.content.Context getApplicationContext()>()
            else if (stmt instanceof JAssignStmt && ((JAssignStmt) stmt).getRightOpBox().getValue().getType().toString().equals("android.content.Context") && ((JAssignStmt) stmt).getRightOpBox().getValue().toString().contains("getApplicationContext") && !ApplicationClassFilter.isAndroidSystemAPI(Rgex.getSubUtilSimple(((JAssignStmt) stmt).getRightOpBox().getValue().toString(), "(<.*>)"))) {
                caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(((JAssignStmt) stmt).getLeftOp(), Jimple.v().newStaticInvokeExpr(
                        Scene.v().getMethod("<androidx.test.InstrumentationRegistry: android.content.Context getTargetContext()>").makeRef()
                )));
            } else if (stmt instanceof JIdentityStmt && ((JIdentityStmt) stmt).getRightOpBox().getValue() instanceof ParameterRef && ((JIdentityStmt) stmt).getRightOpBox().getValue().getType().toString().equals("android.os.Bundle")) {
                caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(((JIdentityStmt) stmt).getLeftOp(), Jimple.v().newNewExpr(
                        RefType.v("android.os.Bundle")
                )));
                caseModel.baseBoxStmts.add(i, Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((Local) ((JIdentityStmt) stmt).getLeftOp(), Scene.v().getMethod("<android.os.Bundle: void <init>()>").makeRef())));

            } else if (stmt instanceof JIdentityStmt && ((JIdentityStmt) stmt).getRightOpBox().getValue() instanceof ParameterRef && ((JIdentityStmt) stmt).getRightOpBox().getValue().getType().toString().equals("android.app.Activity")) {
                Local newLocal = Jimple.v().newLocal("newActivityTestRule1", RefType.v("androidx.test.rule.ActivityTestRule"));

                caseModel.baseBoxStmts.add(i, Jimple.v().newAssignStmt(newLocal, Jimple.v().newNewExpr(RefType.v("androidx.test.rule.ActivityTestRule"))));
                caseModel.baseBoxStmts.add(i, Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(newLocal, Scene.v().getMethod("<androidx.test.rule.ActivityTestRule: void <init>(java.lang.Class)>").makeRef(),
                        new ImmediateBox(ClassConstant.v("android/app/Activity")).getValue())));
                caseModel.baseBoxStmts.add(i, Jimple.v().newAssignStmt(((JIdentityStmt) stmt).getLeftOp(), Jimple.v().newSpecialInvokeExpr(newLocal, Scene.v().getMethod("<androidx.test.rule.ActivityTestRule: android.app.Activity getActivity()>").makeRef())));
                caseModel.baseBoxStmts.remove(i + 3);
            } else if (stmt instanceof JIdentityStmt && ((JIdentityStmt) stmt).getRightOpBox().getValue() instanceof ParameterRef && ((JIdentityStmt) stmt).getRightOpBox().getValue().getType().toString().equals("android.content.Intent")) {
                caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(((JIdentityStmt) stmt).getLeftOp(), Jimple.v().newNewExpr(
                        RefType.v("android.content.Intent")
                )));
                caseModel.baseBoxStmts.add(i, Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((Local) ((JIdentityStmt) stmt).getLeftOp(), Scene.v().getMethod("<android.content.Intent: void <init>()>").makeRef())));

            } else if (stmt instanceof JIdentityStmt && ((JIdentityStmt) stmt).getRightOpBox().getValue() instanceof ParameterRef) {
//                caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(((JIdentityStmt) stmt).getLeftOp(), Jimple.v().newStaticInvokeExpr(
//                        Scene.v().getMethod("<org.easymock.EasyMock: java.lang.Object createMock(java.lang.Class)>").makeRef(),
//                        new ImmediateBox(ClassConstant.v(((JIdentityStmt) stmt).getRightOpBox().getValue().getType().toString().replaceAll("\\.", "\\/"))).getValue()
//                )));

                Value leftLocal = ((JIdentityStmt) stmt).getLeftOp();
                ParameterRef parameterRef = (ParameterRef) ((JIdentityStmt) stmt).getRightOpBox().getValue();
                SootMethod paramSootMethod = getConstructorMethod(Scene.v().getSootClass(parameterRef.getType().toString()));

                if (paramSootMethod.getParameterCount() == 0) {
                    if (paramSootMethod.isStatic()) {
                        caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(leftLocal, Jimple.v().newStaticInvokeExpr(paramSootMethod.makeRef())));
                    } else {
                        caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(leftLocal, Jimple.v().newNewExpr(RefType.v(parameterRef.getType().toString()))));
                        caseModel.baseBoxStmts.add(i, Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((Local) leftLocal, paramSootMethod.makeRef())));
                    }
                } else {
                    List<Value> paramList = new ArrayList<>();
                    boolean isPossible2MockPrams = true;
                    for (int paramNo = 0; paramNo < paramSootMethod.getParameterCount(); paramNo++) {
                        Type currentParam = paramSootMethod.getParameterType(paramNo);
                        if (currentParam instanceof PrimType) {
                            Local currentParamLocal = Jimple.v().newLocal("baseParam" + paramNo, currentParam);
                            Value currentParamValue = newPrimType((PrimType) currentParam);
                            body.getLocals().add(currentParamLocal);
                            units.add(Jimple.v().newAssignStmt(currentParamLocal, currentParamValue));
                            paramList.add(currentParamValue);
                        } else if (currentParam instanceof ArrayType && currentParam.getArrayType().baseType instanceof PrimType) {
                            Local currentParamLocal = Jimple.v().newLocal("baseParam" + paramNo, currentParam);
                            Value currentParamValue = newNewArrayExpr((PrimType) currentParam.getArrayType().baseType);
                            body.getLocals().add(currentParamLocal);
                            units.add(Jimple.v().newAssignStmt(currentParamLocal, currentParamValue));
                            paramList.add(currentParamValue);
                        } else if (ApplicationClassFilter.isJavaBasicType(currentParam.toString())) {
                            Local currentParamLocal = Jimple.v().newLocal("baseParam" + paramNo, currentParam);
                            Value currentParamValue = newPrimType2(currentParam.toString());
                            body.getLocals().add(currentParamLocal);
                            units.add(Jimple.v().newAssignStmt(currentParamLocal, currentParamValue));
                            paramList.add(currentParamValue);
                        } else if (currentParam instanceof ArrayType
                                && currentParam.getArrayType().baseType instanceof RefType
                                && ApplicationClassFilter.isJavaBasicType(currentParam.getArrayType().baseType.toString())) {
                            Local currentParamLocal = Jimple.v().newLocal("baseParam" + paramNo, currentParam);
                            newNewArrayExpr2(currentParam.toString(), units, currentParamLocal, body, currentParamLocal.getName());
                            body.getLocals().add(currentParamLocal);
                            paramList.add(currentParamLocal);
                        } else if (ApplicationClassFilter.isClassInSystemPackage(currentParam.toString())) {
                            Local currentParamLocal = Jimple.v().newLocal("baseParam" + paramNo, currentParam);
                            body.getLocals().add(currentParamLocal);
                            SootMethod constructorMethod = getConstructorMethod(Scene.v().getSootClass(currentParam.toString()));
                            if (constructorMethod.isStatic()) {
                                units.add(Jimple.v().newAssignStmt(currentParamLocal, Jimple.v().newStaticInvokeExpr(constructorMethod.makeRef())));
                            } else {
                                units.add(Jimple.v().newAssignStmt(currentParamLocal, Jimple.v().newNewExpr(RefType.v(currentParam.toString()))));
                                units.add(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(currentParamLocal, constructorMethod.makeRef())));
                            }
                            paramList.add(currentParamLocal);
                        } else {
                            isPossible2MockPrams = false;
                        }
                    }
                    if (isPossible2MockPrams) {
                        if (paramSootMethod.isStatic()) {
                            caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(leftLocal, Jimple.v().newStaticInvokeExpr(paramSootMethod.makeRef())));
                        } else {
                            caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(leftLocal, Jimple.v().newNewExpr(RefType.v(parameterRef.getType().toString()))));
                            caseModel.baseBoxStmts.add(i, Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((Local) leftLocal, paramSootMethod.makeRef(), paramList)));
                        }
                    } else {
                        caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(leftLocal, Jimple.v().newStaticInvokeExpr(
                                Scene.v().getMethod("<org.easymock.EasyMock: java.lang.Object createMock(java.lang.Class)>").makeRef(),
                                new ImmediateBox(ClassConstant.v(parameterRef.getType().toString().replaceAll("\\.", "\\/"))).getValue()
                        )));
                    }
                }
            }
        }
    }

    private static void processSuperMethodInvocation(APITestCaseModel caseModel) {
        if (CollectionUtils.isEmpty(caseModel.baseBoxStmts)) {
            return;
        }
        for (int i = caseModel.baseBoxStmts.size() - 1; i >= 0; i--) {
            if (!(caseModel.baseBoxStmts.get(i) instanceof Stmt)) {
                continue;
            }
            Stmt stmt = (Stmt) caseModel.baseBoxStmts.get(i);

            if (stmt instanceof JIdentityStmt
                    && ((JIdentityStmt) stmt).getRightOpBox().getValue() instanceof ThisRef
                    && !ApplicationClassFilter.isAndroidSystemAPI(((JIdentityStmt) stmt).getRightOp().toString())) {

                SootClass sc = Scene.v().getSootClass(((JIdentityStmt) stmt).getRightOpBox().getValue().getType().toString());
                if (sc.hasSuperclass() && sc.getSuperclass().getName().equals("android.app.Service")) {
                    caseModel.baseBoxStmts.set(i, Jimple.v().newAssignStmt(((JIdentityStmt) stmt).getLeftOp(), Jimple.v().newNewExpr(Scene.v().getSootClass("com.example.android.testing.unittesting.basicunitandroidtest.MyService").getType())));
                    caseModel.baseBoxStmts.add(i, Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((Local) ((JIdentityStmt) stmt).getLeftOp(), Scene.v().getMethod("<com.example.android.testing.unittesting.basicunitandroidtest.MyService: void <init>()>").makeRef())));
                }
            }

        }
    }

    private static void addThisIdentityStmt(APITestCaseModel caseModel, SootClass sClass, JimpleBody body, Chain units) {
        if (caseModel.unit.toString().contains("$r10 = virtualinvoke $r8.<android.content.pm.PackageManager: android.content.pm.PackageInfo getPackageInfo(java.lang.String,int)>($r9, 0)")) {
            System.out.println("j");
        }
        if (caseModel.apiType != APIType.STATIC_INVOKE) {
            Unit lastUnit = getLastUnit(caseModel);
            if (!(lastUnit.toString().contains("@this:"))) {
                Local thisLocal = Jimple.v().newLocal("thisObject", RefType.v(sClass.getName()));
                body.getLocals().add(thisLocal);
                units.add(Jimple.v().newIdentityStmt(thisLocal, Jimple.v().newIdentityRefBox(new ThisRef(RefType.v(sClass.getName()))).getValue()));
            }
        }
    }

    private static Unit getLastUnit(APITestCaseModel caseModel) {
        for (int i = caseModel.baseBoxStmts.size() - 1; i >= 0; i--) {
            if (caseModel.baseBoxStmts.get(i) instanceof Unit) {
                return (Unit) caseModel.baseBoxStmts.get(i);
            }
        }
        return null;
    }

    private static void processActivityArchitecture(APITestCaseModel caseModel, SootClass sClass) {
        if (CollectionUtils.isEmpty(caseModel.baseBoxStmts)) {
            return;
        }
        //check if we need to add UI Activity Architecture
        boolean needCreateActivityArchitecture = false;
        for (int i = caseModel.baseBoxStmts.size() - 1; i >= 0; i--) {
            if (!(caseModel.baseBoxStmts.get(i) instanceof Stmt)) {
                continue;
            }
            Stmt stmt = (Stmt) caseModel.baseBoxStmts.get(i);

            if (stmt instanceof JIdentityStmt
                    && ((JIdentityStmt) stmt).getRightOp() instanceof ThisRef
                    && caseModel.declareMethod.getDeclaringClass().getSuperclass().getName().contains("Activity")) {
                needCreateActivityArchitecture = true;
                caseModel.needCreateActivityArchitecture = true;
            }
        }

        if (needCreateActivityArchitecture) {
            // add Filed
            SootField mActivityRule = new SootField("mActivityRule", RefType.v("androidx.test.rule.ActivityTestRule"), Modifier.PUBLIC);
            sClass.addField(mActivityRule);
            AnnotationGenerator.v().annotate(mActivityRule, Rule.class, new ArrayList<>());

            SootMethod initMethod = new SootMethod("<init>", null, VoidType.v(), Modifier.PUBLIC);
            sClass.addMethod(initMethod);
            JimpleBody initBody = Jimple.v().newBody(initMethod);
            initMethod.setActiveBody(initBody);
            Chain initUnits = initBody.getUnits();

            SootFieldRef sootFieldRef = new AbstractSootFieldRef(sClass, "mActivityRule", RefType.v("androidx.test.rule.ActivityTestRule"), false);

            Local tmpLocal = Jimple.v().newLocal("thisActivity", RefType.v(sClass.getName()));
            initBody.getLocals().add(tmpLocal);
            initUnits.add(Jimple.v().newIdentityStmt(tmpLocal, Jimple.v().newIdentityRefBox(new ThisRef(RefType.v(sClass.getName()))).getValue()));

            Local newLocal = Jimple.v().newLocal("newActivityTestRule", RefType.v("androidx.test.rule.ActivityTestRule"));
            initBody.getLocals().add(newLocal);
            initUnits.add(Jimple.v().newAssignStmt(newLocal, Jimple.v().newNewExpr(RefType.v("androidx.test.rule.ActivityTestRule"))));
            initUnits.add(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(newLocal, Scene.v().getMethod("<androidx.test.rule.ActivityTestRule: void <init>(java.lang.Class)>").makeRef(),
                    new ImmediateBox(ClassConstant.fromType(Scene.v().getSootClass("com.example.android.testing.unittesting.basicunitandroidtest.MyActivity").getType())).getValue())));
            initUnits.add(Jimple.v().newAssignStmt(Jimple.v().newInstanceFieldRef(tmpLocal, sootFieldRef), newLocal));
            initUnits.add(Jimple.v().newReturnVoidStmt());

            //replace this to thisActivity(tmpLocal)
            for (int i = caseModel.baseBoxStmts.size() - 1; i >= 0; i--) {
                if (!(caseModel.baseBoxStmts.get(i) instanceof Stmt)) {
                    continue;
                }
                Stmt stmt = (Stmt) caseModel.baseBoxStmts.get(i);

                if (stmt instanceof JIdentityStmt
                        && ((JIdentityStmt) stmt).rightBox instanceof IdentityRefBox
                        && ((JIdentityStmt) stmt).rightBox.getValue() instanceof ThisRef
                        && ((JIdentityStmt) stmt).rightBox.getValue().toString().contains(caseModel.declareMethod.getDeclaringClass().getName())
                ) {
                    Local newActivity = Jimple.v().newLocal("newActivity", RefType.v(caseModel.declareMethod.getDeclaringClass().getName()));
                    Stmt replaceStmt = Jimple.v().newAssignStmt(((JIdentityStmt) stmt).getLeftOp(), Jimple.v().newSpecialInvokeExpr(newActivity, Scene.v().getMethod("<androidx.test.rule.ActivityTestRule: android.app.Activity getActivity()>").makeRef()));
                    caseModel.baseBoxStmts.set(i, replaceStmt);
                    caseModel.baseBoxStmts.add(i + 1, Jimple.v().newAssignStmt(newActivity, Jimple.v().newInstanceFieldRef(tmpLocal, sootFieldRef)));
                    caseModel.baseBoxStmts.add(i + 2, Jimple.v().newIdentityStmt(tmpLocal, Jimple.v().newIdentityRefBox(new ThisRef(RefType.v(sClass.getName()))).getValue()));
                }
            }
        }
    }

    private static void calculateMinContext(SootMethod targetMethod, List<Unit> units, int i) {
        Unit u = units.get(i);
        SootMethod method = AnalysisParameters.v().getIcfg().getMethodOf(u);

        if (method == null) {
            return;
        }

        String unitString = Rgex.getSubUtilSimple(u.toString(), "(<.*>)");
        if (!ApplicationClassFilter.isAndroidSystemAPI(unitString)
                || ApplicationClassFilter.isAndroidUIMethod(unitString)
        ) {
            return;
        }

        /**
         * process AssignStmt
         */
        if (u instanceof JAssignStmt && ((JAssignStmt) u).getRightOp() instanceof InvokeExpr) {
            String systemAPI = ((JAssignStmt) u).getInvokeExpr().getMethod().getSignature();
            if (GlobalRef.allSystemAPI.contains(systemAPI)) {
                return;
            } else {
                GlobalRef.allSystemAPI.add(systemAPI);
                APITestCaseModel apiTestCaseModel = processAssignStmt(targetMethod, u, systemAPI);
                GlobalRef.apiTestCaseModelList.add(apiTestCaseModel);
            }
        }
        /**
         * process statement
         */
        if ((Stmt) u instanceof InvokeStmt) {
            String systemAPI = ((JInvokeStmt) u).getInvokeExpr().getMethod().getSignature();
            if (GlobalRef.allSystemAPI.contains(systemAPI)) {
                return;
            } else {
                GlobalRef.allSystemAPI.add(systemAPI);
                APITestCaseModel apiTestCaseModel = processInvocationStmt(targetMethod, u, systemAPI);
                GlobalRef.apiTestCaseModelList.add(apiTestCaseModel);
            }
        }
    }

    private static APITestCaseModel processAssignStmt(SootMethod targetMethod, Unit u, String systemAPI) {
        InvokeExpr invokeExpr = (InvokeExpr) ((JAssignStmt) u).getRightOp();

        APITestCaseModel apiTestCaseModel = new APITestCaseModel();
        apiTestCaseModel.apiType = APIType.getType(invokeExpr);
        apiTestCaseModel.unit = u;
        apiTestCaseModel.declareMethod = targetMethod;
        apiTestCaseModel.apiSignature = systemAPI;

        String baseBox = null;
        try {
            baseBox = Rgex.getSubUtilSimple(invokeExpr.toString(), "(\\$[a-z][0-9]+.<)").replace(".<", "");
            if (StringUtils.isBlank(baseBox) && !APIType.toStringType(apiTestCaseModel.apiType).equals("STATIC_INVOKE")) {
                baseBox = Rgex.getSubUtilSimple(invokeExpr.toString(), "([a-z]+[0-9]+.<)").replace(".<", "");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<ValueBox> baseBoxList = invokeExpr.getUseBoxes();
        for (ValueBox valueBox : baseBoxList) {
            if (!valueBox.getValue().toString().equals(baseBox)) {
                continue;
            }
            List<Object> result = ArgumentValueManager
                    .v()
                    .getArgumentValueAnalysis(Constants.DefaultArgumentTypes.Scalar.CLASS)
                    .computeVariableValues(valueBox.getValue(), (Stmt) u);

            List<Object> filterResult = result.stream().filter(res -> {
                return !res.toString().equals(Constants.ANY_STRING);
            }).collect(Collectors.toList());

            //add to apiTestCaseModel
            if (APIType.toStringType(apiTestCaseModel.apiType).equals("VIRTUAL_INVOKE")
                    || APIType.toStringType(apiTestCaseModel.apiType).equals("SPECIAL_INVOKE")
                    || APIType.toStringType(apiTestCaseModel.apiType).equals("INTERFACE_INVOKE")
            ) {
                if (valueBox.getValue().toString().equals(baseBox)) {
                    apiTestCaseModel.baseBox = valueBox;
                    apiTestCaseModel.baseBoxStmts = filterResult;
                } else {
                    Parameter parameter = new Parameter();
                    parameter.paramSig = valueBox;
                    parameter.units = filterResult;
                    apiTestCaseModel.params.add(parameter);
                }
            } else if (APIType.toStringType(apiTestCaseModel.apiType).equals("STATIC_INVOKE")) {
                Parameter parameter = new Parameter();
                parameter.paramSig = valueBox;
                parameter.units = filterResult;
                apiTestCaseModel.params.add(parameter);
            } else {
                System.out.println("=======error========else hhhhh:" + u.toString());
            }
        }
        return apiTestCaseModel;
    }

    private static APITestCaseModel processInvocationStmt(SootMethod targetMethod, Unit u, String systemAPI) {
        APITestCaseModel apiTestCaseModel = new APITestCaseModel();
        apiTestCaseModel.apiType = APIType.getType((Stmt) u);
        apiTestCaseModel.unit = u;
        apiTestCaseModel.declareMethod = targetMethod;
        apiTestCaseModel.apiSignature = systemAPI;

        String baseBox = null;
        try {
            baseBox = Rgex.getSubUtilSimple(u.toString(), "(\\$[a-z][0-9]+.<)").replace(".<", "");
            if (StringUtils.isBlank(baseBox) && !APIType.toStringType(apiTestCaseModel.apiType).equals("STATIC_INVOKE")) {
                baseBox = Rgex.getSubUtilSimple(u.toString(), "([a-z]+[0-9]+.<)").replace(".<", "");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<ValueBox> baseBoxList = ((JInvokeStmt) u).getInvokeExpr().getUseBoxes();
        for (ValueBox valueBox : baseBoxList) {
            if (!valueBox.getValue().toString().equals(baseBox)) {
                continue;
            }
            List<Object> result = ArgumentValueManager
                    .v()
                    .getArgumentValueAnalysis(Constants.DefaultArgumentTypes.Scalar.CLASS)
                    .computeVariableValues(valueBox.getValue(), (Stmt) u);

            List<Object> filterResult = result.stream().filter(res -> {
                return !res.toString().equals(Constants.ANY_STRING);
            }).collect(Collectors.toList());

            //add to apiTestCaseModel
            if (APIType.toStringType(apiTestCaseModel.apiType).equals("VIRTUAL_INVOKE")
                    || APIType.toStringType(apiTestCaseModel.apiType).equals("SPECIAL_INVOKE")
                    || APIType.toStringType(apiTestCaseModel.apiType).equals("INTERFACE_INVOKE")
            ) {
                if (valueBox.getValue().toString().equals(baseBox)) {
                    apiTestCaseModel.baseBox = valueBox;
                    apiTestCaseModel.baseBoxStmts = filterResult;
                } else {
                    Parameter parameter = new Parameter();
                    parameter.paramSig = valueBox;
                    parameter.units = filterResult;
                    apiTestCaseModel.params.add(parameter);
                }
            } else if (APIType.toStringType(apiTestCaseModel.apiType).equals("STATIC_INVOKE")) {
                Parameter parameter = new Parameter();
                parameter.paramSig = valueBox;
                parameter.units = filterResult;
                apiTestCaseModel.params.add(parameter);
            } else {
                System.out.println("=======error========else hhhhh:" + u.toString());
            }
        }
        return apiTestCaseModel;
    }

    private static JimpleBasedInterproceduralCFG initialize(String apkPath, String forceAndroidJar) throws FileNotFoundException, ParseException {
        File file = new File(apkPath);
        GlobalRef.apkName = file.getName().replace(".apk", "");

        List<String> supportJars = new ArrayList<>();
        supportJars.add("res/androidx.test.rules.jar");
        supportJars.add("res/androidx.test.runner.jar");
        supportJars.add("res/androidx.test.core.jar");
        supportJars.add("res/androidx.test.monitor.jar");
        supportJars.add("res/easymock-4.2.jar");
        supportJars.add("res/BaseUnitTest.jar");

        //calculate EntryPoint to generate dummyMainMethod
        System.out.println(apkPath);

        // Initialize Soot
        SootConfigForAndroid sootConf = new SootConfigForAndroid() {
            @Override
            public void setSootOptions(Options options, InfoflowConfiguration config) {
                super.setSootOptions(options, config);
                // your optins here
                List<String> setting = new ArrayList<>();
                setting.addAll(supportJars);
                setting.add(apkPath);
                options.v().set_process_dir(setting);
            }
        };

        SetupApplication analyser = new SetupApplication(forceAndroidJar, apkPath);
        analyser.setSootConfig(sootConf);
        analyser.constructCallgraph();

        //Initialize COAL Model
        String[] coalArgs = {
                "-model", GlobalRef.coalModelPath,
                "-cp", forceAndroidJar,
                "-input", GlobalRef.SOOTOUTPUT
        };
        DefaultCommandLineParser parser = new DefaultCommandLineParser();
        DefaultCommandLineArguments commandLineArguments = parser.parseCommandLine(coalArgs, DefaultCommandLineArguments.class);
        Model.loadModel(commandLineArguments.getModel());

        JimpleBasedInterproceduralCFG iCfg = new PropagationIcfg();
        AnalysisParameters.v().setIcfg(iCfg);
        ArgumentValueManager.v().registerDefaultArgumentValueAnalyses();
        FieldTransformerManager.v().registerDefaultFieldTransformerFactories();

        GlobalRef.iCfg = iCfg;
        return iCfg;
    }

    public static void copy(File file, File destFile) {
        BufferedInputStream bis = null;
        BufferedOutputStream bos = null;
        try {
            bis = new BufferedInputStream(new FileInputStream(file));
            bos = new BufferedOutputStream(new FileOutputStream(destFile));
            byte[] bys = new byte[1024];
            int len = 0;
            while ((len = bis.read(bys)) != -1) {
                bos.write(bys, 0, len);

            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (bis != null) {
                try {
                    bis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void printTestCaseModelList() {
        for (APITestCaseModel apiTestCaseModel : GlobalRef.apiTestCaseModelList) {
            System.out.println("====================================================Minor Context============================================================");
            System.out.println("DeclareMethod:" + apiTestCaseModel.declareMethod);
            System.out.println("InvokeStmt:" + apiTestCaseModel.unit);
            String testCaseName = ("TestCase_" + GlobalRef.apkName + "_" + apiTestCaseModel.unit.toString().hashCode()).replace(".", "_").replace("-", "_");
            System.out.println("TestCase_name:" + testCaseName);

            if (APIType.toStringType(apiTestCaseModel.apiType).equals("VIRTUAL_INVOKE")
                    || APIType.toStringType(apiTestCaseModel.apiType).equals("SPECIAL_INVOKE")
                    || APIType.toStringType(apiTestCaseModel.apiType).equals("INTERFACE_INVOKE")) {
                System.out.println("-------------baseBoxStmt---------------");

                for (Object baseBoxStmt : apiTestCaseModel.baseBoxStmts) {
                    System.out.println(baseBoxStmt.toString());
                }
            }

            for (int i = 0; i < apiTestCaseModel.params.size(); i++) {
                System.out.println("-------------paramStmt" + i + "---------------");
                for (Object unit : apiTestCaseModel.params.get(i).units) {
                    System.out.println(unit);
                }
            }
        }

    }

}
