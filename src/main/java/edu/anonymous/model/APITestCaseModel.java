package edu.anonymous.model;

import soot.SootMethod;
import soot.Unit;
import soot.ValueBox;

import java.util.ArrayList;
import java.util.List;

public class APITestCaseModel {

    public SootMethod declareMethod;

    public Unit unit;

    public APIType apiType;

    public ValueBox baseBox;

    public List<Object> baseBoxStmts;

    public List<Parameter> params = new ArrayList<>();

    public boolean needCreateActivityArchitecture = false;

    /**
     * Follwing fields are used for constructed data formatting
     */
    public String sootClassName;

    public String apiSignature;


}