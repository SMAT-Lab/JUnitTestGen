package edu.anonymous.model;

import soot.Value;
import soot.jimple.*;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JVirtualInvokeExpr;

public enum APIType {

    VIRTUAL_INVOKE,

    SPECIAL_INVOKE,

    STATIC_INVOKE,

    INTERFACE_INVOKE,

    NONE,

    ;

    public static APIType getType(Stmt stmt){
        if (! stmt.containsInvokeExpr())
        {
            throw new RuntimeException(stmt + " must contain an invoke expression.");
        }

        Value stmtValue = ((JInvokeStmt) stmt).getInvokeExprBox().getValue();
        if (stmtValue instanceof JSpecialInvokeExpr){
            return SPECIAL_INVOKE;
        }else if(stmtValue instanceof JVirtualInvokeExpr){
            return VIRTUAL_INVOKE;
        }else if(stmtValue instanceof StaticInvokeExpr){
            return STATIC_INVOKE;
        }else if(stmtValue instanceof InterfaceInvokeExpr){
            return INTERFACE_INVOKE;
        }

        return NONE;
    }

    public static APIType getType(InvokeExpr invokeExpr){
        if (invokeExpr instanceof JSpecialInvokeExpr){
            return SPECIAL_INVOKE;
        }else if(invokeExpr instanceof JVirtualInvokeExpr){
            return VIRTUAL_INVOKE;
        }else if(invokeExpr instanceof StaticInvokeExpr){
            return STATIC_INVOKE;
        }else if(invokeExpr instanceof InterfaceInvokeExpr){
            return INTERFACE_INVOKE;
        }

        return NONE;
    }

    public static String toStringType(APIType type)
    {
        switch (type)
        {
            case VIRTUAL_INVOKE:
                return "VIRTUAL_INVOKE";
            case SPECIAL_INVOKE:
                return "SPECIAL_INVOKE";
            case STATIC_INVOKE:
                return "STATIC_INVOKE";
            case INTERFACE_INVOKE:
                return "INTERFACE_INVOKE";
            default:
                return "NONE";
        }
    }
}
