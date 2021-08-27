package edu.anonymous;

import edu.anonymous.model.APITestCaseModel;
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG;

import java.util.*;

public class GlobalRef
{
	public static String apkPath;
	public static String pkgName;
	public static String apkVersionName;
	public static int apkVersionCode = -1;
	public static int apkMinSdkVersion;
	public static Set<String> apkPermissions;

	//Configuration files
	public static final String WORKSPACE = "workspace";
	public static final String SOOTOUTPUT = "sootOutput";
	public static String fieldCallsConfigPath = "res/FieldCalls.txt";
	public static String coalModelPath = "res/reflection_simple.model";


	public static JimpleBasedInterproceduralCFG iCfg;

	public static List<APITestCaseModel> apiTestCaseModelList = new ArrayList<>();

	public static Set<String> allSystemAPI = new HashSet<>();
	public static HashMap<String, String> utNameAPISigMap = new HashMap<>();

	public static String apkName;
}
