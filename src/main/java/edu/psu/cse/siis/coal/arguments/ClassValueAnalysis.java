/*
 * Copyright (C) 2015 The Pennsylvania State University and the University of Wisconsin
 * Systems and Internet Infrastructure Security Laboratory
 *
 * Author: Damien Octeau
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.psu.cse.siis.coal.arguments;

import edu.anonymous.utils.ApplicationClassFilter;
import edu.anonymous.utils.Rgex;
import edu.psu.cse.siis.coal.AnalysisParameters;
import edu.psu.cse.siis.coal.Constants;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.scalar.Pair;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An argument value analysis for class types.
 */
public class ClassValueAnalysis extends BackwardValueAnalysis {
    private static final String TOP_VALUE = Constants.ANY_CLASS;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public List<Object> computeInlineArgumentValues(String[] inlineValues) {
        return new ArrayList<>(Arrays.asList(inlineValues));
    }

    /**
     * Returns the set of possible values of a variable of type class.
     *
     * @param value The variable whose value we are looking for.
     * @param start The statement where the analysis should start.
     * @return The set of possible values for the variable.
     */
    @Override
    public List<Object> computeVariableValues(Value value, Stmt start) {
        if (value instanceof ClassConstant) {
            return Collections.singletonList((Object) ((ClassConstant) value).getValue());
        } else if (value instanceof Local) {
            //original assignmentList
            List<DefinitionStmt> assignmentList = findAssignmentsForLocal(start, (Local) value, true, new HashSet<Pair<Unit, Local>>());

            //process assignStmts（enrichAssignmentList）
            List<DefinitionStmt> enrichAssignmentList = new ArrayList<>();
            if(assignmentList.get(0).getRightOp() instanceof JInstanceFieldRef && !ApplicationClassFilter.isAndroidSystemAPI(Rgex.getSubUtilSimple(assignmentList.get(0).toString(), "(<.*>)"))){
                //if assignmentList is an JInstanceFieldRef, then we would process later:[ClassValueAnalysis#JInstanceFieldRef]
                enrichAssignmentList.addAll(assignmentList);
            }else {
                enrichAssignmentList.addAll(assignmentList);
                for (DefinitionStmt assignStmt : assignmentList) {
                    Value rhsValue = assignStmt.getRightOp();
                    if (rhsValue instanceof JStaticInvokeExpr && !ApplicationClassFilter.isAndroidSystemAPI(((JStaticInvokeExpr) rhsValue).getMethodRef().toString())) {
                        continue;
                    } else if (CollectionUtils.isNotEmpty(rhsValue.getUseBoxes())) {
                        List<DefinitionStmt> additionalAssignments = handleVariableInvoke(rhsValue, assignStmt, new HashSet<>());
                        enrichAssignmentList.addAll(additionalAssignments);
                    }
                }
            }

            return processAssignments(enrichAssignmentList);

        } else if (value instanceof Constant) {
            return Collections.singletonList((Object) value);
        } else {
            return Collections.singletonList((Object) TOP_VALUE);
        }
    }

    private List<Object> processAssignments(List<DefinitionStmt> enrichAssignmentList) {
        List<Object> result = new ArrayList<>();

        List<Object> objects = null;
        objects = processClassAssignments(enrichAssignmentList, new HashSet<Stmt>());

        if (objects.size() == 1 && objects.contains(TOP_VALUE)) {
            for (DefinitionStmt definitionStmt : enrichAssignmentList) {
                result.add(definitionStmt);
            }
            return result;
        } else {
            result.addAll(objects);
            return result;
        }
    }

    private static String convert2PatternMethod(String s) {
        Pattern pattern = Pattern.compile("(<.*>)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(s);
        if (matcher.find()) {
            return matcher.group(1);

        }
        return s;
    }

    /**
     * Processes assignment to local variables that have a class type.
     *
     * @param assignStmts  A list of assignment statements to a given local variable.
     * @param visitedStmts The set of statements visited by the analysis.
     * @return The set of possible value given by the assignment statements.
     */
    private List<Object> processClassAssignments(List<DefinitionStmt> assignStmts,
                                                 Set<Stmt> visitedStmts) {
        List<Object> result = new ArrayList<>();

        for (DefinitionStmt assignStmt : assignStmts) {
            Value rhsValue = assignStmt.getRightOp();

            if (rhsValue instanceof ClassConstant) {
                result.add(assignStmt);
            } else if (rhsValue instanceof ParameterRef) {
                if (ApplicationClassFilter.isAndroidSystemPackage(rhsValue.getType().toString())) {
                    result.add(assignStmt);
                } else {
                    ParameterRef parameterRef = (ParameterRef) rhsValue;
                    //result.add(assignStmt);
                    Iterator<Edge> edges =
                            Scene.v().getCallGraph()
                                    .edgesInto(AnalysisParameters.v().getIcfg().getMethodOf(assignStmt));
                    while (edges.hasNext()) {
                        Edge edge = edges.next();
                        InvokeExpr invokeExpr = edge.srcStmt().getInvokeExpr();
                        if (visited(visitedStmts, edge)) continue;

                        visitedStmts.add(edge.srcStmt());
                        if (ApplicationClassFilter.isClassInSystemPackage(invokeExpr.getMethod().getDeclaringClass().getName())
                                || ((SootMethod) edge.getSrc()).getDeclaringClass().toString().contains("dummyMainClass")) {
                            //result.add(edge);
                        } else {
                            //result.add(edge);
                            List<Object> newResults =
                                    computeVariableValues(invokeExpr.getArg(parameterRef.getIndex()), edge.srcStmt());
                            if(newResults.get(0) instanceof StringConstant){
                                result.add(Jimple.v().newAssignStmt(assignStmt.getLeftOp(), (StringConstant)newResults.get(0)));
                            } else if (newResults.contains(TOP_VALUE) || newResults.contains(Constants.ANY_STRING)) {
                                return Collections.singletonList((Object) TOP_VALUE);
                                //return Collections.singleton((Object) rhsValue);
                            } else if(newResults.get(0) instanceof JAssignStmt){
                                result.add(Jimple.v().newAssignStmt(assignStmt.getLeftOp(),  ((JAssignStmt) newResults.get(0)).getLeftOp()));
                                result.addAll(newResults);
                            }else {
                                result.addAll(newResults);
                            }
                        }
                    }
                }
            } else if (rhsValue instanceof InvokeExpr) {
                InvokeExpr invokeExpr = (InvokeExpr) rhsValue;
                SootMethod method = invokeExpr.getMethod();
                if (method.getSignature().equals(
                        "<java.lang.Class: java.lang.Class forName(java.lang.String)>")
                        || method.getSignature().equals(
                        "<java.lang.ClassLoader: java.lang.Class loadClass(java.lang.String)>")) {
                    List<Object> classNames =
                            ArgumentValueManager.v()
                                    .getArgumentValueAnalysis(Constants.DefaultArgumentTypes.Scalar.STRING)
                                    .computeVariableValues(invokeExpr.getArg(0), assignStmt);
                    if (classNames.contains(TOP_VALUE)) {
                        return Collections.singletonList((Object) TOP_VALUE);
                    } else {
                        result.addAll(classNames);
                    }
                } else if (method.getSignature().equals("<java.lang.Object: java.lang.Class getClass()>")) {
                    VirtualInvokeExpr virtualInvokeExpr = (VirtualInvokeExpr) invokeExpr;
                    if (logger.isDebugEnabled()) {
                        logger.debug("Returning " + virtualInvokeExpr.getBase().getType().toString());
                    }
                    return Collections.singletonList((Object) virtualInvokeExpr.getBase().getType().toString());
                } else {

                    //result.add(assignStmt);

                    //如果是system API就停止了
                    if (ApplicationClassFilter.isClassSystemPackage(convert2PatternMethod(assignStmt.toString()))
                    || ApplicationClassFilter.isAndroidSystemAPI(invokeExpr)) {
                        result.add(assignStmt);
                    } else {
                        List<Object> invokeExprs = handleInvokeExpression(assignStmt, visitedStmts);

                        if (CollectionUtils.isNotEmpty(invokeExprs)) {
                            if(invokeExprs.get(0) instanceof JAssignStmt){
                                result.add(Jimple.v().newAssignStmt(assignStmt.getLeftOp(),  ((JAssignStmt) invokeExprs.get(0)).getLeftOp()));
                                result.addAll(invokeExprs);
                            }else{
                                result.add(assignStmt);
                            }
                        }
                    }
                }
            } else if (rhsValue instanceof JInstanceFieldRef) {
                if (visited(visitedStmts, assignStmt)) continue;
                visitedStmts.add(assignStmt);

                //result.add(assignStmt);

                AtomicBoolean flag = new AtomicBoolean(false);
                SootMethod method = AnalysisParameters.v().getIcfg().getMethodOf(assignStmt);
                method.getDeclaringClass().getMethods().forEach(eachMethod -> {
                    if (eachMethod.hasActiveBody()) {
                        eachMethod.getActiveBody().getUnits().forEach(item -> {
                            if (item instanceof JAssignStmt
                                    && ((JAssignStmt) item).getLeftOp().toString().equals(rhsValue.toString())
                            ) {
                                if (!(((JAssignStmt) item).getRightOp() instanceof NullConstant)) {
                                    flag.set(true);
                                    //generate a new assignStmt: assignStmt.left = item.right;
                                    result.add(Jimple.v().newAssignStmt(assignStmt.getLeftOp(), ((JAssignStmt) item).getRightOp()));

                                    List<Object> fieldResults = ArgumentValueManager
                                            .v()
                                            .getArgumentValueAnalysis(Constants.DefaultArgumentTypes.Scalar.CLASS)
                                            .computeVariableValues(((JAssignStmt) item).getRightOp(), (Stmt) item);

                                    result.addAll(fieldResults);
                                }
                            }
                        });
                    }
                });

                if(!flag.get()){
                    result.add(assignStmt);
                }
            } else if (rhsValue instanceof StaticFieldRef && rhsValue.getType().toString().equals("java.lang.String")) {
                if (visited(visitedStmts, assignStmt)) continue;
                visitedStmts.add(assignStmt);

                ((StaticFieldRef) rhsValue).getField().getDeclaringClass().getMethods().forEach(eachMethod -> {
                    if (eachMethod.hasActiveBody()) {
                        eachMethod.getActiveBody().getUnits().forEach(item -> {
                            if (item instanceof JAssignStmt
                                    && ((JAssignStmt) item).getLeftOp().toString().equals(rhsValue.toString())
                            ) {
                                if (!(((JAssignStmt) item).getRightOp() instanceof NullConstant)) {
                                    //generate a new assignStmt: assignStmt.left = item.right;
                                    result.add(Jimple.v().newAssignStmt(assignStmt.getLeftOp(), ((JAssignStmt) item).getRightOp()));

                                    List<Object> fieldResults = ArgumentValueManager
                                            .v()
                                            .getArgumentValueAnalysis(Constants.DefaultArgumentTypes.Scalar.CLASS)
                                            .computeVariableValues(((JAssignStmt) item).getRightOp(), (Stmt) item);

                                    result.addAll(fieldResults);
                                }
                            }
                        });
                    }
                });
            } else if (rhsValue instanceof StaticFieldRef  && ApplicationClassFilter.isAndroidSystemPackage(((StaticFieldRef) rhsValue).getField().getType().toString())) {

                result.add(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((JimpleLocal)(assignStmt.getLeftOp()), Scene.v().getSootClass(((StaticFieldRef) rhsValue).getField().getType().toString()).getMethods().get(0).makeRef()) ));
                result.add(Jimple.v().newAssignStmt(assignStmt.getLeftOp(), Jimple.v().newNewExpr(RefType.v(((StaticFieldRef) rhsValue).getField().getType().toString()))));

            } else if (rhsValue instanceof JNewExpr) {
                if (visited(visitedStmts, assignStmt)) continue;
                visitedStmts.add(assignStmt);

                if(!ApplicationClassFilter.isAndroidSystemPackage(rhsValue.getType().toString())
                        && ((JNewExpr) rhsValue).getBaseType().getSootClass().hasSuperclass()
                        && ApplicationClassFilter.isAndroidSystemPackage(((JNewExpr) rhsValue).getBaseType().getSootClass().getSuperclass().toString())){

                    result.add(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((JimpleLocal)(assignStmt.getLeftOp()), Scene.v().getSootClass(((JNewExpr) rhsValue).getBaseType().getSootClass().getSuperclass().toString()).getMethods().get(0).makeRef())));
                    result.add(Jimple.v().newAssignStmt(assignStmt.getLeftOp(), Jimple.v().newNewExpr(((JNewExpr) rhsValue).getBaseType().getSootClass().getSuperclass().getType())));
                } else {

                    SootMethod method = AnalysisParameters.v().getIcfg().getMethodOf(assignStmt);
                    Unit initUnit = getInitUnit(assignStmt, method);
                    //Unit initUnit = method.getActiveBody().getUnits().getSuccOf(assignStmt);
                    List<ValueBox> initParams = initUnit.getUseBoxes().stream().filter(a -> {
                        return !a.getValue().toString().equals(assignStmt.getLeftOp().toString()) && !(a instanceof InvokeExprBox);
                    }).collect(Collectors.toList());

                    result.add(initUnit);
                    result.add(assignStmt);

                    initParams.forEach(useBox -> {
                        List<Object> initUnitResults = ArgumentValueManager
                                .v()
                                .getArgumentValueAnalysis(Constants.DefaultArgumentTypes.Scalar.CLASS)
                                .computeVariableValues(useBox.getValue(), (Stmt) initUnit);
                        result.addAll(initUnitResults);
                    });
                }
            } else {
                result.add(assignStmt);
            }
        }

        if (result.size() == 0) {
            return Collections.singletonList((Object) TOP_VALUE);
        }
        return result;
    }

    private Unit getInitUnit(Unit startStmt, SootMethod method) {
        Unit currentUnit = method.getActiveBody().getUnits().getSuccOf(startStmt);
        if (currentUnit != null && currentUnit.toString().contains("<init>")) {
            return currentUnit;
        } else {
            return getInitUnit(currentUnit, method);
        }
    }


    private boolean visited(Set<Stmt> visitedStmts, Edge edge) {
        if (visitedStmts.contains(edge.srcStmt())) {
            return true;
        }
        AtomicBoolean visitedFlag = new AtomicBoolean(false);
        visitedStmts.forEach(visitedStmt -> {
            if (visitedStmt.toString().contains(edge.getTgt().toString())) {
                visitedFlag.set(true);
            }
        });
        return visitedFlag.get();
    }

    private boolean visited(Set<Stmt> visitedStmts, Stmt stmt) {
        return visitedStmts.contains(stmt);
    }


    private List<DefinitionStmt> handleVariableInvoke(Value rhsValue, Unit start, Set<ValueBox> visitedVariables) {
        List<DefinitionStmt> result = new ArrayList<>();
        List<ValueBox> rightBoxes = extractLocalBox(rhsValue.getUseBoxes());

        if (CollectionUtils.isNotEmpty(rightBoxes)) {
            for (ValueBox useBox : rightBoxes) {
                if (visitedVariables.contains(useBox)) {
                    continue;
                }
                visitedVariables.add(useBox);
                if (useBox.getValue() instanceof Local) {
                    List<DefinitionStmt> assignmentList = findAssignmentsForLocal(start, (Local) useBox.getValue(), true, new HashSet<Pair<Unit, Local>>());
                    result.add(assignmentList.get(0));
//                    if (ApplicationClassFilter.isClassSystemPackage(convert2PatternMethod(assignmentList.get(0).toString()))) {
//                        cont//                    } else {
                    Value rop = assignmentList.get(0).getRightOp();
                    if (rop instanceof Local) {
                        result.addAll(handleVariableInvoke(rop, assignmentList.get(0), visitedVariables));

                    }
                    if (CollectionUtils.isNotEmpty(rop.getUseBoxes())) {
                        result.addAll(handleVariableInvoke(rop, assignmentList.get(0), visitedVariables));
                    }
                    //}
                }
            }
        }
        return result;
    }

    private List<ValueBox> extractLocalBox(List<ValueBox> boxes) {
//    if(CollectionUtils.isEmpty(boxes)){
//      return boxes;
//    }
//    List<ValueBox> res = new ArrayList<>();
//    for (ValueBox useBox : boxes) {
//      if (useBox instanceof JimpleLocalBox) {
//        res.add(useBox);
//      }
//    }
//    return res;
        return boxes;
    }

    /**
     * Returns the invocation expressions that are associated with an call statement.
     *
     * @param sourceStmt   The statement at which we should start.
     * @param visitedStmts The set of visited statements.
     * @return The set of possible values.
     */
    protected List<Object> handleInvokeExpression(Stmt sourceStmt, Set<Stmt> visitedStmts) {
        if (visitedStmts.contains(sourceStmt)) {
            return Collections.singletonList(sourceStmt);
        } else {
            visitedStmts.add(sourceStmt);
        }

        //return cgHelper.collectInvokeAPIs(sourceStmt);

        Iterator<Edge> edges = Scene.v().getCallGraph().edgesOutOf(sourceStmt);
        List<Object> result = new ArrayList<>();

        while (edges.hasNext()) {
            Edge edge = edges.next();
            SootMethod target = edge.getTgt().method();
            if (target.isConcrete()) {
                for (Unit unit : target.getActiveBody().getUnits()) {
                    if (unit instanceof ReturnStmt) {
                        ReturnStmt returnStmt = (ReturnStmt) unit;

                        Value returnValue = returnStmt.getOp();
                        if (returnValue instanceof Local) {

                            List<DefinitionStmt> assignStmts =
                                    findAssignmentsForLocal(returnStmt, (Local) returnValue, true,
                                            new HashSet<Pair<Unit, Local>>());

                            if(convert2PatternMethod(assignStmts.get(0).toString()).startsWith("<java.lang.StringBuilder")){

                                List<Object> returnResults = ArgumentValueManager
                                        .v()
                                        .getArgumentValueAnalysis(Constants.DefaultArgumentTypes.Scalar.CLASS)
                                        .computeVariableValues(returnValue, assignStmts.get(0));

                                result.addAll(returnResults);

                            } else if (ApplicationClassFilter.isClassSystemPackage(convert2PatternMethod(assignStmts.get(0).toString()))) {
                                result.add(assignStmts.get(0));
                                if(assignStmts.get(0).getRightOp() instanceof JInstanceFieldRef){
                                    List<Object> res = ArgumentValueManager
                                            .v()
                                            .getArgumentValueAnalysis(Constants.DefaultArgumentTypes.Scalar.CLASS)
                                            .computeVariableValues(assignStmts.get(0).getRightOp().getUseBoxes().get(0).getValue(), assignStmts.get(0));
                                    result.addAll(res);
                                }
                            }else if(assignStmts.get(0) instanceof JAssignStmt && CollectionUtils.isNotEmpty(assignStmts.get(0).getRightOp().getUseBoxes())){
                                for(ValueBox valueBox : assignStmts.get(0).getRightOp().getUseBoxes()){
                                    List<Object> res = ArgumentValueManager
                                            .v()
                                            .getArgumentValueAnalysis(Constants.DefaultArgumentTypes.Scalar.CLASS)
                                            .computeVariableValues(valueBox.getValue(), assignStmts.get(0));
                                    result.addAll(res);
                                }
                            } else {
                                List<Object> classAssignments = processClassAssignments(assignStmts, visitedStmts);
                                if (classAssignments == null || classAssignments.contains(TOP_VALUE)
                                        || classAssignments.contains(Constants.ANY_STRING)) {
                                    return null;
                                } else {
                                    result.addAll(classAssignments);
                                }
                            }
                        } else if (returnValue instanceof InvokeStmt) {
                            result.add(returnValue);
                        } else if (returnValue instanceof Constant) {
                            result.add(returnValue);
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public Object getTopValue() {
        return TOP_VALUE;
    }

}
