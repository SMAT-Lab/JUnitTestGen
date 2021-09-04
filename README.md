# JUnitTestGen
Automatic unit test cases generating


## Setup
The following is required to set up JUnitTestGen:
* MAC system
* IntelliJ IDEA

##### Step 1: Load dependencies to your local repository
* git clone
* cd JUnitTestGen
* ./res/loadDependencies.sh

##### Step 2: build package：
mvn clean install

##### Step 3: example of running JUnitTestGen(3 parameters):
* Parameters are needed here: [your_apk_path.apk],[path of android.jar],[path of result.csv]
* Example: your_path/905a4f82bc194334a046afa9bf29eed7.apk, your_path/android-platforms/android-17/android.jar, your_path/result.csv
       
## Output
* Refer to sootOutput/ folder to check all the generated test cases.
* Refer to [path of result.csv] to check the map of test case name and its corresponding targetAPI 
