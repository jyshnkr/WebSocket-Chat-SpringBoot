# WebSocket-Chat-SpringBoot
WebSocket Chat is an Online Group chat application built using WebSocket API/Protocol in SpringBoot framework. This Application will let the users chat with others.

# Pre-Requisites for the project
* IDE: Apache NetBeans 12.6
* JDK: Temurin 17 JDK
* Tools: Apache Maven
* Platform: Windows 10
* Git Client: GitHub Desktop

# Instructions to Run the Project

This section contains of two subsections:

#### A)Installation:
1. Install [Apache NetBeans 12.6](https://netbeans.apache.org/download/index.html) on your System.
2. Install [Temurin JDK 17](https://adoptium.net/?variant=openjdk17&jvmVariant=hotspot). Make sure you select **Temurin 17(LTS)** option.

    a) Select *''set JAVA_HOME variable''* option while installing the JDK on your System. Please refer to the image below.

    b) After installation is Done, make sure the Path is set in System Environment variables

3. Install [Apache Maven](https://maven.apache.org/download.cgi) on your system. Navigate to the System environment variables as we did earlier in JDK Path and add the Path to both User variables and System variables.

4. Open the Application Apache NetBeans 12.6 on your system. On the top navigation bar, click on 'Tools' and select 'plugins' and now select 'available plugins' and in the search bar - type 'nb-springboot'. Install the plug-in. [If you face any issues while installing plugins like 'Proxy connection issues'. Please click on the proxy settings popped by netbeans and click on 'No Proxy' and retry. If the problem persists, restart the NetBeans and navigate to plugins and install 'nb-springboot' plugin. It shall work now.].

5. Download the project from GitHub as zip folder or clone down the project [WebSocketChat-SpringBoot](https://github.com/jyshnkr/WebSocket-Chat-SpringBoot)

6. Open the NetBeans. In the File option of Top navigation bar, select the 'Open project' and select the downloaded Project.

7. Before proceeding to run the application, please make sure you followed all the Instructions mentioned above.

#### B)Run the project

1. Open the project in netbeans and navigate to the Main class of the project i.e., **WebSocketChatApplication.java**
2. Now, click on the 'Run' menu of top navigation bar and select 'Clean and Build Project'

3. Now, the Project will search for errors and rectifies them. In the same flow, the maven will look for the required dependencies in the project and downloads them for us, if required.

4. Now, select the Main Class 'WebSocketChatApplication.java' and right click on whitespace and click run.

5. Check the console/terminal below for the Status. It will show project 'BUILD SUCCESS' and the 'Server started at localhost 6369'. Please Stop TomCat Server, if you already have one installed and running in your system. This will override my custom server port 6369.

6. Now, open a browser and type 'localhost:6369' and the webpage will look like this. please refer to the image below.

    ![chatWindow](/Images/Installation/chatPage.jpeg)
