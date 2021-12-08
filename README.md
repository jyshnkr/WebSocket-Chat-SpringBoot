# WebSocket-Chat-SpringBoot
A WebSocket Chat Application using SpringBoot Framework

# Pre-Requisites for the project
IDE: Apache NetBeans 12.6
JDK: Temurin 17 JDK
Tools: Apache Maven
Platform: Windows 10
Git Client: GitHub Desktop

# Instructions to Run the Project

This section contains of two subsections:

#### A)Installation:
1. Install [Apache NetBeans 12.6](https://netbeans.apache.org/download/index.html) on your System.
2. Install [Temurin JDK 17](https://adoptium.net/?variant=openjdk17&jvmVariant=hotspot). Make sure you select **Temurin 17(LTS)** option.

    a) Select *''set JAVA_HOME variable''* option while installing the JDK on your System. Please refer to the image below.

    ![JDKPAth](/Images/Installation/JDKtoPath1.jpeg)

    ![JDKPathSet](/Images/Installation/JDKtoPath2.jpeg)

    b) After installation is Done, make sure the Path is set in System Environment variables ( Press Windows + E and Right click on white space and click 'properties' in context menu. In the control panel, click on Advanced system settings on left side and click on Environment Variables in popup menu ). Please refer to the image below.

    ![JDKPath3](/Images/Installation/JDKtoPATH3.jpeg)

3. Install [Apache Maven](https://maven.apache.org/download.cgi) on your system. Make sure to download the file that ends with *.zip*. Please refer to the image below. After download, extract the zipped folder into desired location.

    ![Apache Maven Download](/Images/Installation/MavenDownload.jpeg)

   a) Now, navigate to the bin folder of the extracted apache maven folder. Click on Path navigator in windows explorer (looks like URL bar in browser). Select the Path highlighted in Blue color. Copy it. Please refer to the image below.

    ![MavenPathSelection](/Images/Installation/MavenPathHighlighted.jpeg)

   b) To make sure everything is good, navigate to the System environment variables as we did earlier in JDK Path and add the Path copied in the above step and paste it in 'Path' in both User variables and System variables. Please refer to the Image below.

    ![MavenToPath](/Images/Installation/MAVENtoPATH.jpeg)

4. We're done with Installations. Now, open the Apache NetBeans 12.6 on your system. On the top navigation bar, click on 'Tools' and select 'plugins' and now select 'available plugins' and in the search bar - type 'nb-springboot'. From the results, click on the 'nb-springboot' checkbox and click on install. [If you face any issues while installing plugins like 'Proxy connection issues'. Please click on the proxy settings popped by netbeans and click on 'No Proxy' and retry. If the problem persists, restart the NetBeans and navigate to plugins and install 'nb-springboot' plugin. It shall work now.]. After installing the 'nb-springboot' plugin, netbeans prompts with restart options. Click on restart now. Please refer to the image below.

    ![PluginInstallation](/Images/Installation/Springboot.jpeg)

    ![SpringBootPluginInstallation](/Images/Installation/SpringBootPluginInstallation.jpeg)

5. Now, download the project from GitHub as zip folder or clone down the project or extract the project downloaded from canvas [WebSocketChat-SpringBoot](https://github.com/jyshnkr/WebSocket-Chat-SpringBoot)

6. Open the NetBeans. In the File option of Top navigation bar, select the 'Open project' and select the downloaded Project.

7. Before proceeding to run the application, please make sure you followed all the Instructions mentioned above.

#### B)Run the project

1. Open the project in netbeans and navigate to the Main class of the project i.e., **WebSocketChatApplication.java**
(Project > Source Packages > com.ADPFProject.SpringBoot.WebSocketChatApplication > WebSocketChatApplication.java). Please refer to the image below.

    ![Main](/Images/Installation/mainClass.jpeg)

2. Now, click on the 'Run' menu of top navigation bar and select 'Clean and Build Project'

    ![clean](/Images/Installation/Clean.jpeg)

3. Now, the Project will search for errors and rectifies them. In the same flow, the maven will look for the required dependencies in the project and downloads them for us, if required.

4. Now, select the Main Class 'WebSocketChatApplication.java' and right click on whitespace and click run.
Please refer to the image below.

    ![run](/Images/Installation/run.jpeg)

5. Check the console/terminal below for the Status. It will show project 'BUILD SUCCESS' and the 'Server started at localhost 6369'.

    ![localhost](/Images/Installation/localHost.jpeg)

6. Now, open a browser and type 'localhost:6369' and the webpage will look like this. please refer to the image below.

    ![chatWindow](/Images/Installation/chatPage.jpeg)
