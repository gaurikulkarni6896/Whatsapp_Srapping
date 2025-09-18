package com.whatsapp_cv;

import java.io.File;
import java.io.FileInputStream;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.NoSuchElementException;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import io.github.bonigarcia.wdm.WebDriverManager;

class CVCode {

    // PostgreSQL DB CONFIG
    private static final String DB_URL = "jdbc:postgresql://45.118.162.101:6657/postgres";
    private static final String DB_USER = "appadmin";
    private static final String DB_PASSWORD = "Meraqui@123";

    public static void main(String[] args) throws InterruptedException {
        WebDriverManager.chromedriver().setup();

        String downloadDir = "C:\\Users\\kiran\\OneDrive\\Desktop\\WhatsAppCVs";

        // Chrome download settings
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", downloadDir);
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);

        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("prefs", prefs);
        options.addArguments(
                "--remote-allow-origins=*",
                "--disable-popup-blocking",
                "--disable-notifications",
                "start-maximized",
                "user-data-dir=C:/Users/kiran/AppData/Local/Temp/WhatsAppWebProfile"
        );

        WebDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        try {
            // === STEP 1: Open WhatsApp and login ===
            driver.get("https://web.whatsapp.com");
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("pane-side")));
            System.out.println("✅ WhatsApp Web loaded. Please ensure you're logged in.");

            // === STEP 2: Open chat and download docs ===
            String chatName = "Testing"; // Replace with your group name
            openChatAndDownloadDocs(driver, wait, chatName, downloadDir);

            System.out.println("=== Finished downloading documents ===");

            // === STEP 3: Push downloaded docs into DB ===
            saveFolderFilesToDB(downloadDir);

            System.out.println("=== All downloaded files saved into DB ===");

        } finally {
            // driver.quit(); // Keep open if you want session alive
        }
    }

    // Open a chat and download all docs
    private static void openChatAndDownloadDocs(WebDriver driver, WebDriverWait wait, String chatName, String downloadDir) throws InterruptedException {
        WebElement searchBox = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("div[contenteditable='true'][data-tab='3']")));
        searchBox.clear();
        searchBox.sendKeys(chatName);
        Thread.sleep(2000);

        WebElement chatResult = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//span[@title='" + chatName + "']")));
        chatResult.click();
        System.out.println("📂 Opened chat: " + chatName);

        WebElement messagesPane = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div#main")));

        Set<String> downloadedDocs = new HashSet<>();
        long endTime = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();

        while (System.currentTimeMillis() < endTime) {
            List<WebElement> docs = driver.findElements(
                    By.xpath("//span[contains(text(), '.pdf') or contains(text(), '.docx') or contains(text(), '.zip')]/ancestor::div[@role='button']")
            );

            System.out.println("Docs currently visible: " + docs.size());

            for (WebElement doc : docs) {
                try {
                    String docText = doc.getText().trim();
                    if (downloadedDocs.contains(docText)) continue;

                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", doc);
                    Thread.sleep(100);
                    doc.click();
                    System.out.println("⬇️ Clicked doc: " + docText);

                    File downloadedFile = waitForDownload(downloadDir, 10);

                    if (downloadedFile != null) {
                        System.out.println("✅ Download finished: " + downloadedFile.getName());
                        downloadedDocs.add(docText);
                    } else {
                        System.out.println("❌ Timeout: " + docText + " not downloaded.");
                    }

                    try {
                        WebElement closeBtn = driver.findElement(By.cssSelector("span[data-icon='x-light']"));
                        closeBtn.click();
                        Thread.sleep(500);
                    } catch (NoSuchElementException ignored) {}

                    Thread.sleep(2000);

                } catch (Exception e) {
                    System.out.println("⚠️ Could not download doc: " + e.getMessage());
                }
            }

            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollTop = arguments[0].scrollTop - 200;", messagesPane);
            Thread.sleep(200);
        }
    }

    // Save all files from folder to DB
    private static void saveFolderFilesToDB(String folderPath) {
        File folder = new File(folderPath);

        File[] files = folder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".pdf")
                        || name.toLowerCase().endsWith(".docx")
                        || name.toLowerCase().endsWith(".zip"));

        if (files == null || files.length == 0) {
            System.out.println("⚠️ No matching files found in folder: " + folderPath);
            return;
        }

        for (File file : files) {
            saveFileToDB(file);
        }
    }

    // Save one file into DB
    private static void saveFileToDB(File file) {
    	String sql = "INSERT INTO whatsapp_cv.cv_documents (filename, file_type, file_data) " +
                "VALUES (?, ?, ?) " +
                "ON CONFLICT (filename) DO UPDATE " +
                "SET file_type = EXCLUDED.file_type, " +
                "    file_data = EXCLUDED.file_data";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(sql);
             FileInputStream fis = new FileInputStream(file)) {

            stmt.setString(1, file.getName());
            stmt.setString(2, getFileExtension(file));
            stmt.setBinaryStream(3, fis, file.length()); // ✅ use long

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                System.out.println("💾 Saved into DB: " + file.getName());
            } else {
                System.out.println("⚠️ Skipped duplicate: " + file.getName());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Get file extension
    private static String getFileExtension(File file) {
        String name = file.getName();
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i + 1) : "";
    }

    // Wait for download completion
    private static File waitForDownload(String folderPath, int timeoutSeconds) throws InterruptedException {
        File downloadDir = new File(folderPath);
        long waitUntil = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < waitUntil) {
            File[] files = downloadDir.listFiles((dir, name) ->
                    (name.toLowerCase().endsWith(".pdf")
                            || name.toLowerCase().endsWith(".docx")
                            || name.toLowerCase().endsWith(".zip"))
                            && !name.endsWith(".crdownload")
            );

            if (files != null && files.length > 0) {
                return Arrays.stream(files)
                        .max(Comparator.comparingLong(File::lastModified))
                        .orElse(null);
            }
            Thread.sleep(1000);
        }
        return null;
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

