#!/bin/bash

mvn install:install-file -Dfile=./res/coal-strings-0.1.4.jar -DgroupId=edu.psu.cse.siis -DartifactId=coal-strings -Dversion=0.1.4 -Dpackaging=jar
mvn install:install-file -Dfile=./res/heros-1.0.1-SNAPSHOT.jar -DgroupId=heros -DartifactId=heros -Dversion=1.0.1-SNAPSHOT -Dpackaging=jar
mvn install:install-file -Dfile=./res/soot-infoflow-android-2.7.1.jar -DgroupId=de.tud.sse -DartifactId=soot-infoflow-android -Dversion=2.7.1 -Dpackaging=jar
mvn install:install-file -Dfile=./res/androidx.test.runner.jar -DgroupId=edu.anonymous -DartifactId=androidx.test.runner -Dversion=1.3.0 -Dpackaging=jar
mvn install:install-file -Dfile=./res/androidx.test.rules.jar -DgroupId=edu.anonymous -DartifactId=androidx.test.rules -Dversion=1.3.0 -Dpackaging=jar
mvn install:install-file -Dfile=./res/androidx.test.core.jar -DgroupId=edu.anonymous -DartifactId=androidx.test.core -Dversion=1.3.0 -Dpackaging=jar
mvn install:install-file -Dfile=./res/androidx.test.monitor.jar -DgroupId=edu.anonymous -DartifactId=androidx.test.monitor -Dversion=1.2.0 -Dpackaging=jar
mvn install:install-file -Dfile=./res/easymock-4.2.jar -DgroupId=edu.anonymous -DartifactId=easymock -Dversion=4.2 -Dpackaging=jar
