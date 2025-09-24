package com.whatsapp_cv;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;
import io.github.bonigarcia.wdm.WebDriverManager;

class CVCode {

    private static final String DB_URL = "jdbc:postgresql://45.118.162.101:6657/postgres";
    private static final String DB_USER = "appadmin";
    private static final String DB_PASSWORD = "Meraqui@123";

    public static void main(String[] args) throws InterruptedException {
        WebDriverManager.chromedriver().setup();
        String downloadDir = "C:\\Users\\kiran\\OneDrive\\Desktop\\WhatsAppCVs";
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", downloadDir);
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("prefs", prefs);
        options.addArguments("start-maximized",
                             "--remote-allow-origins=*",
                             "--disable-popup-blocking",
                             "--disable-notifications",
                             "user-data-dir=C:/Users/kiran/AppData/Local/Temp/WhatsAppWebProfile");
        WebDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        try {
            driver.get("https://web.whatsapp.com");
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("pane-side")));
            System.out.println("WhatsApp Web loaded. Please ensure you're logged in.");
            openChatAndDownloadDocs(driver, wait, "Testing", downloadDir);
            System.out.println("=== Finished downloading documents ===");
            saveFolderFilesToDB(downloadDir);
            System.out.println("=== All downloaded files saved into DB ===");
        } finally {
            // driver.quit();
        }
    }

    private static void openChatAndDownloadDocs(WebDriver driver, WebDriverWait wait, String chatName, String downloadDir) throws InterruptedException {
        WebElement searchBox = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("div[contenteditable='true'][data-tab='3']")));
        searchBox.clear();
        searchBox.sendKeys(chatName);
        Thread.sleep(2000);
        WebElement chatResult = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//span[@title='" + chatName + "']")));
        chatResult.click();
        System.out.println(" Opened chat: " + chatName);
        
        WebElement messagesPane = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div#main")));
        Set<String> downloadedDocs = new HashSet<>();
        long endTime = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
        while (System.currentTimeMillis() < endTime) {
            List<WebElement> docs = driver.findElements(By.xpath("//span[contains(text(), '.pdf') or contains(text(), '.docx') or contains(text(), '.zip')]/ancestor::div[@role='button']"));
            System.out.println("Docs currently visible: " + docs.size());
            for (WebElement doc : docs) {
                try {
                    String docText = doc.getText().trim();
                    if (downloadedDocs.contains(docText)) continue;
                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", doc);
                    Thread.sleep(100);
                    doc.click();
                    System.out.println("Clicked doc: " + docText);
                    File downloadedFile = waitForDownload(downloadDir, 10);
                    if (downloadedFile != null) {
                        System.out.println(" Download finished: " + downloadedFile.getName());
                        downloadedDocs.add(docText);
                    } else {
                        System.out.println("Timeout: " + docText + " not downloaded.");
                    }
                    ((JavascriptExecutor) driver).executeScript(
                    		  "arguments[0].scrollTop = Math.max(arguments[0].scrollTop - 1000, 0);", messagesPane);
                    		Thread.sleep(1500);

                    try {
                        WebElement closeBtn = driver.findElement(By.cssSelector("span[data-icon='x-light']"));
                        closeBtn.click();
                        Thread.sleep(500);
                    } catch (NoSuchElementException ignored) {}
                    Thread.sleep(2000);
                } catch (Exception e) {
                    System.out.println(" Could not download doc: " + e.getMessage());
                }
            }
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollTop = arguments[0].scrollTop - 200;", messagesPane);
            Thread.sleep(200);
        }
    }

    private static void saveFolderFilesToDB(String folderPath) {
        File folder = new File(folderPath);
        File[] files = folder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".pdf") || name.toLowerCase().endsWith(".docx") || name.toLowerCase().endsWith(".zip"));
        if (files == null || files.length == 0) {
            System.out.println("No matching files found in folder: " + folderPath);
            return;
        }
        for (File file : files) saveFileToDB(file);
    }

    private static void saveFileToDB(File file) {
        try {
            String normalizedName = normalizeFilename(file.getName());
            String fileHash = getFileChecksum(file);

            if (isHashExists(fileHash)) {
                System.out.println(" Skipped duplicate (content): " + file.getName());
                return;
            }
            String sql = "INSERT INTO whatsapp_cv.cv_documents (filename, file_type, file_data, file_hash) VALUES (?, ?, ?, ?) ON CONFLICT (file_hash) DO NOTHING";
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 FileInputStream fis = new FileInputStream(file)) {
                stmt.setString(1, normalizedName);
                stmt.setString(2, getFileExtension(file));
                stmt.setBinaryStream(3, fis, file.length());
                stmt.setString(4, fileHash);
                int rows = stmt.executeUpdate();
                if (rows > 0) System.out.println(" Saved into DB: " + normalizedName);
                else System.out.println(" Skipped duplicate (insert race): " + normalizedName);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static boolean isHashExists(String fileHash) {
        String query = "SELECT 1 FROM whatsapp_cv.cv_documents WHERE file_hash = ? LIMIT 1";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, fileHash);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (Exception e) { return false; }
    }

    private static String normalizeFilename(String filename) {
        return filename.replaceAll(" \\([0-9]+\\)(?=\\.\\w+$)", "");
    }

    private static String getFileExtension(File file) {
        String name = file.getName();
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i + 1) : "";
    }

    private static File waitForDownload(String folderPath, int timeoutSeconds) throws InterruptedException {
        File downloadDir = new File(folderPath);
        long waitUntil = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < waitUntil) {
            File[] files = downloadDir.listFiles((dir, name) ->
                    (name.toLowerCase().endsWith(".pdf") || name.toLowerCase().endsWith(".docx") || name.toLowerCase().endsWith(".zip")) && !name.endsWith(".crdownload"));
            if (files != null && files.length > 0) {
                return Arrays.stream(files)
                        .max(Comparator.comparingLong(File::lastModified))
                        .orElse(null);
            }
            Thread.sleep(1000);
        }
        return null;
    }

    public static String getFileChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new FileInputStream(file)) {
            byte[] byteArray = new byte[1024];
            int bytesCount;
            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}






















//package com.whatsapp_cv;
//import org.openqa.selenium.*;
//import org.openqa.selenium.chrome.ChromeDriver;
//import org.openqa.selenium.chrome.ChromeOptions;
//import org.openqa.selenium.support.ui.ExpectedConditions;
//import org.openqa.selenium.support.ui.WebDriverWait;
//import io.github.bonigarcia.wdm.WebDriverManager;
//import java.io.File;
//import java.time.Duration;
//import java.util.*;
//import java.util.NoSuchElementException;
//
//public class CVCode {
//    public static void main(String[] args) throws InterruptedException {
//        WebDriverManager.chromedriver().setup();
//        
//       
//        // === Download settings ===
//        String downloadDir = "C:/Users/kiran/Downloads/WhatsAppCVs";
//        Map<String, Object> prefs = new HashMap<>();
//        prefs.put("download.default_directory", downloadDir);
//        prefs.put("download.prompt_for_download", false);
//        prefs.put("download.directory_upgrade", true);
//        prefs.put("plugins.always_open_pdf_externally", true); // don't auto-open PDFs
//
//        ChromeOptions options = new ChromeOptions();
//        options.setExperimentalOption("prefs", prefs);
//        options.addArguments(
//                "--remote-allow-origins=*",
//                "--disable-popup-blocking",
//                "--disable-notifications",
//                "start-maximized",
//                "user-data-dir=C:/Users/kiran/AppData/Local/Temp/WhatsAppWebProfile"
//        );
//
//        WebDriver driver = new ChromeDriver(options);
//        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
//
//        try {
//           
//            driver.get("https://web.whatsapp.com");
//            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("pane-side")));
//            System.out.println("WhatsApp Web loaded. Please ensure you're logged in.");
//
//            String chatName = "Testing"; // Replace with your group name
//
//            WebElement searchBox = wait.until(ExpectedConditions.elementToBeClickable(
//                    By.cssSelector("div[contenteditable='true'][data-tab='3']")));
//            searchBox.clear();
//            searchBox.sendKeys(chatName);
//            Thread.sleep(2000);
//
//            WebElement chatResult = wait.until(ExpectedConditions.elementToBeClickable(
//                    By.xpath("//span[@title='" + chatName + "']")));
//            chatResult.click();
//            System.out.println("Opened chat: " + chatName);
//
//            WebElement messagesPane = wait.until(
//                    ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div#main")));
//
//            Set<String> downloadedDocs = new HashSet<>();
//
//            // Scroll + search loop (run for 3 minutes, adjust if needed)
//            long endTime = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
//
//            while (System.currentTimeMillis() < endTime) {
//               
//                List<WebElement> docs = driver.findElements(
//                        By.xpath("//span[contains(text(), '.pdf') or contains(text(), '.docx') or contains(text(), '.zip')]/ancestor::div[@role='button']")
//                );
//
//                System.out.println("Docs currently visible: " + docs.size());
//
//                for (WebElement doc : docs) {
//                    try {
//                        String docText = doc.getText().trim();
//
//                        if (downloadedDocs.contains(docText)) {
//                            continue; // skip already processed
//                        }
//
//                        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", doc);
//                        Thread.sleep(200);
//                        doc.click();
//                        System.out.println("Clicked doc: " + docText);
//
//                        boolean success = waitForDownload(downloadDir, 10);
//
//                        if (success) {
//                            System.out.println("✅ Download finished: " + docText);
//                            downloadedDocs.add(docText);
//                        } else {
//                            System.out.println("❌ Timeout: " + docText + " not downloaded.");
//                        }
//
//                        try {
//                            WebElement closeBtn = driver.findElement(By.cssSelector("span[data-icon='x-light']"));
//                            closeBtn.click();
//                            Thread.sleep(500);
//                        } catch (NoSuchElementException ignored) {}
//
//                        Thread.sleep(2000); // wait before next doc
//
//                    } catch (Exception e) {
//                        System.out.println("⚠️ Could not download doc: " + e.getMessage());
//                    }
//                }
//
//                // Scroll up to load older docs
//                ((JavascriptExecutor) driver).executeScript(
//                        "arguments[0].scrollTop = arguments[0].scrollTop - 500;", messagesPane);
//                Thread.sleep(500);
//            }
//
//            System.out.println("=== Finished checking for documents ===");
//
//        } finally {
//         
//            // driver.quit();
//        }
//    }
//  
//    private static boolean waitForDownload(String folderPath, int timeoutSeconds) throws InterruptedException {
//        File downloadDir = new File(folderPath);
//        long waitUntil = System.currentTimeMillis() + timeoutSeconds * 1000L;
//
//        while (System.currentTimeMillis() < waitUntil) {
//            String[] files = downloadDir.list((dir, name) ->
//                    (name.toLowerCase().endsWith(".pdf")
//                            || name.toLowerCase().endsWith(".docx")
//                            || name.toLowerCase().endsWith(".zip"))
//                            && !name.endsWith(".crdownload")
//            );
//
//            if (files != null && files.length > 0) {
//                return true; // at least one valid file downloaded
//            }
//            Thread.sleep(1000);
//        }
//        return false;
//    }
//}

