Morpheus Plugin For ZStack Cloud
==================

Requirements
------------
-	[gradle](https://services.gradle.org/distributions/gradle-8.3-bin.zip) 8.3
-	java jdk 11


build plugin example
------------
```
- wget https://services.gradle.org/distributions/gradle-8.3-bin.zip -P /tmp
- cd /tmp
- unzip ./gradle-8.3-bin.zip -d /opt/gradle
- vim /etc/profile.d/gradle.sh
--- export GRADLE_HOME=/opt/gradle/gradle-8.3
--- export PATH=${GRADLE_HOME}/bin:${PATH}
- source /etc/profile.d/gradle.sh
- gradle -v (check use version is gradle-8.3)
- yum install java-11-openjdk-devel
- sudo alternatives --config java (select java 11 version)
- java --version (check use version is java 11)

- gradle build
```
build jar path
------------
```
/${project_Path}/build/libs/zstack-0.1.0.jar
```
