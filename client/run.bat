@echo off
REM Lancement de JNoexs avec les flags natifs requis depuis JDK 24+ (JEP 472) :
REM  - javafx.graphics/media/web/swing chargent leurs libs natives via System::load
REM  - usb4java (libusb4java 1.3.0) charge libusb via System::load (module unnamed)
REM Sans --enable-native-access, des WARNING apparaissent et le native access sera
REM BLOQUANT dans une future release du JDK.
REM Le flag ne peut PAS aller dans le manifest du jar (JEP 472 = ligne de commande).
REM JDK 27 LTS : E:\Programmation\Java\jdk-27.0.1.0.2+1 (mettre JAVA_HOME si besoin)
set "JAVA_HOME=E:\Programmation\Java\jdk-27.0.1.0.2+1"
"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED,javafx.base,javafx.graphics,javafx.controls,javafx.fxml,javafx.media,javafx.swing,javafx.web -jar shade\noexs.jar %*
