package com.adityachandel.booklore.service.metadata.writer;

import com.adityachandel.booklore.model.MetadataClearFlags;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.BookMetadataEntity;
import com.adityachandel.booklore.model.enums.BookFileType;
import com.adityachandel.booklore.service.FileFingerprint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;

@Slf4j
@Component
public class CbxMetadataWriter implements MetadataWriter {

    private static final String DEFAULT_COVER_ENTRY = "cover.jpg";
    private static final int BUFFER_SIZE = 8192;

    private static class CoverPayload {
        private final byte[] bytes;
        private final int width;
        private final int height;

        private CoverPayload(byte[] bytes, int width, int height) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
        }

        private byte[] getBytes() {
            return bytes;
        }

        private int getWidth() {
            return width;
        }

        private int getHeight() {
            return height;
        }
    }

    @Override
    public void replaceCoverImageFromUpload(BookEntity bookEntity, MultipartFile multipartFile) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            log.warn("Cover upload failed: empty or null file.");
            return;
        }

        replaceCoverImage(bookEntity, () -> {
            try {
                return prepareCoverPayload(multipartFile.getBytes());
            } catch (Exception e) {
                log.warn("Failed to read uploaded cover image: {}", e.getMessage(), e);
                return null;
            }
        });
    }

    @Override
    public void replaceCoverImageFromUrl(BookEntity bookEntity, String url) {
        if (url == null || url.isBlank()) {
            log.warn("Cover update via URL failed: empty or null URL.");
            return;
        }

        replaceCoverImage(bookEntity, () -> {
            try {
                byte[] bytes = loadImageBytes(url);
                return prepareCoverPayload(bytes);
            } catch (Exception e) {
                log.warn("Failed to load cover image from {}: {}", url, e.getMessage(), e);
                return null;
            }
        });
    }

    private void replaceCoverImage(BookEntity bookEntity, Supplier<CoverPayload> payloadSupplier) {
        if (bookEntity == null) {
            log.warn("Cover update skipped: book entity is null.");
            return;
        }

        CoverPayload payload = payloadSupplier.get();
        if (payload == null || payload.getBytes().length == 0) {
            log.warn("Cover update skipped for {}: no valid image data available.", bookEntity.getFileName());
            return;
        }

        try {
            applyCoverToArchive(bookEntity, payload);
        } catch (Exception e) {
            log.warn("Failed to persist cover image for {}: {}", bookEntity.getFileName(), e.getMessage(), e);
        }
    }

    private CoverPayload prepareCoverPayload(byte[] rawBytes) throws Exception {
        if (rawBytes == null || rawBytes.length == 0) {
            return null;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes)) {
            BufferedImage source = ImageIO.read(bais);
            if (source == null) {
                log.warn("Unable to decode cover image bytes.");
                return null;
            }

            BufferedImage rgbImage = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgbImage.createGraphics();
            graphics.drawImage(source, 0, 0, Color.WHITE, null);
            graphics.dispose();

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                if (!ImageIO.write(rgbImage, "jpg", baos)) {
                    log.warn("Failed to encode cover image as JPEG.");
                    return null;
                }
                return new CoverPayload(baos.toByteArray(), rgbImage.getWidth(), rgbImage.getHeight());
            }
        }
    }

    private byte[] loadImageBytes(String pathOrUrl) throws Exception {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return null;
        }

        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            try (InputStream stream = new URL(pathOrUrl).openStream()) {
                return stream.readAllBytes();
            }
        }

        Path path = Path.of(pathOrUrl);
        if (!Files.exists(path)) {
            log.warn("Image path does not exist: {}", pathOrUrl);
            return null;
        }
        return Files.readAllBytes(path);
    }

    private void applyCoverToArchive(BookEntity bookEntity, CoverPayload payload) throws Exception {
        File file = new File(bookEntity.getFullFilePath().toUri());
        String nameLower = file.getName().toLowerCase(Locale.ROOT);

        if (nameLower.endsWith(".cbz")) {
            updateZipArchiveWithCover(bookEntity, file, payload);
        } else if (nameLower.endsWith(".cb7")) {
            updateSevenZArchiveWithCover(bookEntity, file, payload);
        } else if (nameLower.endsWith(".cbr")) {
            updateRarArchiveWithCover(bookEntity, file, payload);
        } else {
            log.warn("Unsupported CBX format for cover update: {}", file.getName());
        }
    }

    private static class ComicInfoContext {
        private final Document document;
        private final String entryName;

        private ComicInfoContext(Document document, String entryName) {
            this.document = document;
            this.entryName = entryName;
        }
    }

    @Override
    public void writeMetadataToFile(File file, BookMetadataEntity metadata, String thumbnailUrl, boolean restoreMode, MetadataClearFlags clearFlags) {
        Path backup = null;
        boolean writeSucceeded = false;
        try {
            // Create a backup next to the source file (temp name, safe to delete later)
            backup = Files.createTempFile(file.getParentFile().toPath(), "cbx_backup_", ".bak");
            Files.copy(file.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            log.warn("Unable to create backup for {}: {}", file.getAbsolutePath(), ex.getMessage(), ex);
        }
        try {
            String nameLower = file.getName().toLowerCase(Locale.ROOT);
            boolean isCbz = nameLower.endsWith(".cbz");
            boolean isCbr = nameLower.endsWith(".cbr");
            boolean isCb7 = nameLower.endsWith(".cb7");

            if (!isCbz && !isCbr && !isCb7) {
                log.warn("Unsupported file type for CBX writer: {}", file.getName());
                return;
            }

            // Build (or load and update) ComicInfo.xml as a Document
            String comicInfoEntryName = null;
            Document doc;
            if (isCbz) {
                try (ZipFile zipFile = new ZipFile(file)) {
                    ZipEntry existing = findComicInfoEntry(zipFile);
                    if (existing != null) {
                        try (InputStream is = zipFile.getInputStream(existing)) {
                            doc = buildSecureDocument(is);
                        }
                        comicInfoEntryName = sanitizeEntryName(existing.getName());
                    } else {
                        doc = newEmptyComicInfo();
                    }
                }
                if (comicInfoEntryName == null || comicInfoEntryName.isBlank()) {
                    comicInfoEntryName = "ComicInfo.xml";
                }
            } else if (isCb7) {
                try (SevenZFile sevenZ = new SevenZFile(file)) {
                    SevenZArchiveEntry existing = null;
                    for (SevenZArchiveEntry e : sevenZ.getEntries()) {
                        if (e != null && !e.isDirectory() && isComicInfoName(e.getName())) {
                            existing = e; break;
                        }
                    }
                    if (existing != null) {
                        try (InputStream is = sevenZ.getInputStream(existing)) {
                            doc = buildSecureDocument(is);
                        }
                        comicInfoEntryName = sanitizeEntryName(existing.getName());
                    } else {
                        doc = newEmptyComicInfo();
                    }
                }
                if (comicInfoEntryName == null || comicInfoEntryName.isBlank()) {
                    comicInfoEntryName = "ComicInfo.xml";
                }
            } else { // CBR
                try (Archive archive = new Archive(file)) {
                    FileHeader existing = findComicInfoHeader(archive);
                    if (existing != null) {
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            archive.extractFile(existing, baos);
                            try (InputStream is = new java.io.ByteArrayInputStream(baos.toByteArray())) {
                                doc = buildSecureDocument(is);
                            }
                        }
                        comicInfoEntryName = sanitizeEntryName(existing.getFileNameString());
                    } else {
                        doc = newEmptyComicInfo();
                    }
                }
                if (comicInfoEntryName == null || comicInfoEntryName.isBlank()) {
                    comicInfoEntryName = "ComicInfo.xml";
                }
            }

            // Apply metadata to the Document
            Element root = doc.getDocumentElement();
            MetadataCopyHelper helper = new MetadataCopyHelper(metadata);
            helper.copyTitle(restoreMode, clearFlags != null && clearFlags.isTitle(), val -> setElement(doc, root, "Title", val));
            helper.copyDescription(restoreMode, clearFlags != null && clearFlags.isDescription(), val -> {
                setElement(doc, root, "Summary", val);
                removeElement(root, "Description");
            });
            helper.copyPublisher(restoreMode, clearFlags != null && clearFlags.isPublisher(), val -> setElement(doc, root, "Publisher", val));
            helper.copySeriesName(restoreMode, clearFlags != null && clearFlags.isSeriesName(), val -> setElement(doc, root, "Series", val));
            helper.copySeriesNumber(restoreMode, clearFlags != null && clearFlags.isSeriesNumber(), val -> setElement(doc, root, "Number", formatFloat(val)));
            helper.copySeriesTotal(restoreMode, clearFlags != null && clearFlags.isSeriesTotal(), val -> setElement(doc, root, "Count", val != null ? val.toString() : null));
            helper.copyPublishedDate(restoreMode, clearFlags != null && clearFlags.isPublishedDate(), date -> setDateElements(doc, root, date));
            helper.copyPageCount(restoreMode, clearFlags != null && clearFlags.isPageCount(), val -> setElement(doc, root, "PageCount", val != null ? val.toString() : null));
            helper.copyLanguage(restoreMode, clearFlags != null && clearFlags.isLanguage(), val -> setElement(doc, root, "LanguageISO", val));
            helper.copyAuthors(restoreMode, clearFlags != null && clearFlags.isAuthors(), set -> {
                setElement(doc, root, "Writer", join(set));
                removeElement(root, "Penciller");
                removeElement(root, "Inker");
                removeElement(root, "Colorist");
                removeElement(root, "Letterer");
                removeElement(root, "CoverArtist");
            });
            helper.copyCategories(restoreMode, clearFlags != null && clearFlags.isCategories(), set -> {
                setElement(doc, root, "Genre", join(set));
                removeElement(root, "Tags");
            });

            // Cleanup whitespace-only text nodes
            normalizeWhitespace(root);

            // Serialize ComicInfo.xml
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            ByteArrayOutputStream xmlBaos = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(xmlBaos));
            byte[] xmlBytes = xmlBaos.toByteArray();
=======
            // Cleanup whitespace-only text nodes
            normalizeWhitespace(root);

            String normalizedComicInfoEntryName = comicInfoEntryName.replace('\\', '/');
            String legacyCoverEntryName = sanitizeEntryName(extractFrontCoverImageName(doc));
            if (legacyCoverEntryName != null) {
                legacyCoverEntryName = legacyCoverEntryName.replace('\\', '/');
            }

            CoverPayload coverPayload = null;
            String coverEntryName = null;
            if (thumbnailUrl != null && !thumbnailUrl.isBlank()) {
                try {
                    byte[] coverBytes = loadImageBytes(thumbnailUrl);
                    coverPayload = prepareCoverPayload(coverBytes);
                    if (coverPayload != null) {
                        coverEntryName = determineCoverEntryName(doc);
                        coverEntryName = coverEntryName.replace('\\', '/');
                        ensureFrontCoverPage(doc, coverEntryName, coverPayload.getWidth(), coverPayload.getHeight());
                    }
                } catch (Exception e) {
                    log.warn("Failed to prepare cover image for ComicInfo.xml from '{}': {}", thumbnailUrl, e.getMessage());
                    coverPayload = null;
                    coverEntryName = null;
                }
            }

            byte[] xmlBytes = documentToBytes(doc);
            boolean replaceCover = coverPayload != null && coverEntryName != null;
>>>>>>> Stashed changes

            // Repack depending on container type; always write to a temp target then atomic move
            if (isCbz) {
                Path temp = Files.createTempFile("cbx_edit", ".cbz");
                try (ZipFile zipFile = new ZipFile(file);
                     ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(temp))) {
                    Enumeration<? extends ZipEntry> entries = zipFile.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String entryName = entry.getName();
                        String normalizedEntry = entryName.replace('\\', '/');
                        if (!isSafeEntryName(entryName)) {
                            log.warn("Skipping unsafe ZIP entry name: {}", entryName);
                            continue;
                        }
                        if (normalizedEntry.equals(normalizedComicInfoEntryName)) {
                            continue;
                        }
                        if (replaceCover) {
                            if (normalizedEntry.equals(coverEntryName)) {
                                continue;
                            }
                            if (legacyCoverEntryName != null && normalizedEntry.equals(legacyCoverEntryName)) {
                                continue;
                            }
                        }
                        zos.putNextEntry(new ZipEntry(normalizedEntry));
                        try (InputStream is = zipFile.getInputStream(entry)) {
                            is.transferTo(zos);
                        }
                        zos.closeEntry();
                    }
                    if (replaceCover) {
                        writeZipEntry(zos, coverEntryName, coverPayload.getBytes());
                    }
                    writeZipEntry(zos, normalizedComicInfoEntryName, xmlBytes);
                }
                atomicReplace(temp, file.toPath());
                writeSucceeded = true;
                return;
            }

            if (isCb7) {
                Path tempZip = Files.createTempFile("cbx_edit", ".cbz");
                try (SevenZFile sevenZ = new SevenZFile(file);
                     ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
                    SevenZArchiveEntry entry;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while ((entry = sevenZ.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        String entryName = entry.getName();
                        String normalizedEntry = entryName.replace('\\', '/');
                        if (!isSafeEntryName(entryName)) {
                            log.warn("Skipping unsafe 7z entry name: {}", entryName);
                            drainSevenZEntry(sevenZ, buffer);
                            continue;
                        }
                        if (isComicInfoName(entryName) || normalizedEntry.equals(normalizedComicInfoEntryName)) {
                            drainSevenZEntry(sevenZ, buffer);
                            continue;
                        }
                        if (replaceCover) {
                            if (normalizedEntry.equals(coverEntryName) ||
                                    (legacyCoverEntryName != null && normalizedEntry.equals(legacyCoverEntryName))) {
                                drainSevenZEntry(sevenZ, buffer);
                                continue;
                            }
                        }
                        zos.putNextEntry(new ZipEntry(normalizedEntry));
                        int read;
                        while ((read = sevenZ.read(buffer, 0, buffer.length)) > 0) {
                            zos.write(buffer, 0, read);
                        }
                        zos.closeEntry();
                    }
                    if (replaceCover) {
                        writeZipEntry(zos, coverEntryName, coverPayload.getBytes());
                    }
                    writeZipEntry(zos, normalizedComicInfoEntryName, xmlBytes);
                }
                Path target = file.toPath().resolveSibling(stripExtension(file.getName()) + ".cbz");
                atomicReplace(tempZip, target);
                try { Files.deleteIfExists(file.toPath()); } catch (Exception ignored) {}
                writeSucceeded = true;
                return;
            }

            // CBR path
            String rarBin = System.getenv().getOrDefault("BOOKLORE_RAR_BIN", "rar");
            boolean rarAvailable = isRarAvailable(rarBin);

            Path tempDir = Files.createTempDirectory("cbx_rar_");
            try {
                extractRarToDirectory(file, tempDir);

                Path comicInfoPath = tempDir.resolve(normalizedComicInfoEntryName).normalize();
                if (!comicInfoPath.startsWith(tempDir)) {
                    throw new IllegalStateException("ComicInfo path escapes extraction directory");
                }
                Files.createDirectories(comicInfoPath.getParent());
                Files.write(comicInfoPath, xmlBytes);

                if (replaceCover) {
                    Path coverPath = tempDir.resolve(coverEntryName).normalize();
                    if (!coverPath.startsWith(tempDir)) {
                        throw new IllegalStateException("Cover path escapes extraction directory");
                    }
                    Files.createDirectories(coverPath.getParent());
                    Files.write(coverPath, coverPayload.getBytes());

                    if (legacyCoverEntryName != null && !legacyCoverEntryName.equals(coverEntryName)) {
                        Path legacyPath = tempDir.resolve(legacyCoverEntryName).normalize();
                        if (legacyPath.startsWith(tempDir)) {
                            Files.deleteIfExists(legacyPath);
                        }
                    }
                }

                if (rarAvailable) {
                    Path targetRar = file.toPath().toAbsolutePath().normalize();
                    String rarExec = isSafeExecutable(rarBin) ? rarBin : "rar";
                    ProcessBuilder pb = new ProcessBuilder(rarExec, "a", "-idq", "-ep1", "-ma5", targetRar.toString(), ".");
                    pb.directory(tempDir.toFile());
                    Process process = pb.start();
                    int code = process.waitFor();
                    if (code == 0) {
                        writeSucceeded = true;
                        return;
                    }
                    log.warn("RAR creation failed with exit code {}. Falling back to CBZ conversion for {}", code, file.getName());
                } else {
                    log.warn("`rar` binary not found. Falling back to CBZ conversion for {}", file.getName());
                }

                Path tempZip = Files.createTempFile("cbx_edit", ".cbz");
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
                    try (Stream<Path> walk = Files.walk(tempDir)) {
                        walk.filter(Files::isRegularFile).forEach(path -> addFileToZip(zos, tempDir, path));
                    }
                } catch (RuntimeException runtimeException) {
                    if (runtimeException.getCause() instanceof Exception cause) {
                        throw cause;
                    }
                    throw runtimeException;
                }

                Path target = file.toPath().resolveSibling(stripExtension(file.getName()) + ".cbz");
                atomicReplace(tempZip, target);
                Files.deleteIfExists(file.toPath());
                writeSucceeded = true;
            } finally {
                deleteDirectoryRecursively(tempDir);
            }
        } catch (Exception e) {
            // Attempt to restore the original file from backup
            try {
                if (backup != null) {
                    Files.copy(backup, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    log.info("Restored original file from backup after failure: {}", file.getAbsolutePath());
                }
            } catch (Exception restoreEx) {
                log.warn("Failed to restore original file from backup: {} -> {}", backup, file.getAbsolutePath(), restoreEx);
            }
            log.warn("Failed to write metadata for {}: {}", file.getName(), e.getMessage(), e);
        } finally {
            if (writeSucceeded && backup != null) {
                try { Files.deleteIfExists(backup); } catch (Exception ignore) {}
            }
        }
    }

    // ----------------------- helpers -----------------------

    private void updateZipArchiveWithCover(BookEntity bookEntity, File file, CoverPayload payload) throws Exception {
        try (ZipFile zipFile = new ZipFile(file)) {
            ZipEntry comicInfoEntry = findComicInfoEntry(zipFile);
            Document doc;
            if (comicInfoEntry != null) {
                try (InputStream is = zipFile.getInputStream(comicInfoEntry)) {
                    doc = buildSecureDocument(is);
                }
            } else {
                doc = newEmptyComicInfo();
            }

            String legacyCoverEntryName = sanitizeEntryName(extractFrontCoverImageName(doc));
            if (legacyCoverEntryName != null) {
                legacyCoverEntryName = legacyCoverEntryName.replace('\\', '/');
            }

            String coverEntryName = determineCoverEntryName(doc).replace('\\', '/');
            ensureFrontCoverPage(doc, coverEntryName, payload.getWidth(), payload.getHeight());
            byte[] xmlBytes = documentToBytes(doc);

            String comicInfoName = comicInfoEntry != null ? comicInfoEntry.getName() : "ComicInfo.xml";
            comicInfoName = sanitizeEntryName(comicInfoName);
            if (comicInfoName == null || comicInfoName.isBlank()) {
                comicInfoName = "ComicInfo.xml";
            }
            String normalizedComicInfoName = comicInfoName.replace('\\', '/');

            Path tempZip = Files.createTempFile("cbx_cover_", ".cbz");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    String normalizedEntry = entryName.replace('\\', '/');
                    if (!isSafeEntryName(entryName)) {
                        log.warn("Skipping unsafe ZIP entry name: {}", entryName);
                        continue;
                    }
                    if (normalizedEntry.equals(normalizedComicInfoName)) {
                        continue;
                    }
                    if (normalizedEntry.equals(coverEntryName)) {
                        continue;
                    }
                    if (legacyCoverEntryName != null && normalizedEntry.equals(legacyCoverEntryName)) {
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry(normalizedEntry));
                    try (InputStream inputStream = zipFile.getInputStream(entry)) {
                        inputStream.transferTo(zos);
                    }
                    zos.closeEntry();
                }

                writeZipEntry(zos, coverEntryName, payload.getBytes());
                writeZipEntry(zos, normalizedComicInfoName, xmlBytes);
            }
            atomicReplace(tempZip, file.toPath());
        }

        refreshFileStats(bookEntity, file.toPath());
    }

    private void updateSevenZArchiveWithCover(BookEntity bookEntity, File file, CoverPayload payload) throws Exception {
        ComicInfoContext context = loadComicInfoFromSevenZ(file);
        Document doc = context.document;
        String comicInfoName = context.entryName != null ? sanitizeEntryName(context.entryName) : null;
        if (comicInfoName == null || comicInfoName.isBlank()) {
            comicInfoName = "ComicInfo.xml";
        }
        String normalizedComicInfoName = comicInfoName.replace('\\', '/');

        String legacyCoverEntryName = sanitizeEntryName(extractFrontCoverImageName(doc));
        if (legacyCoverEntryName != null) {
            legacyCoverEntryName = legacyCoverEntryName.replace('\\', '/');
        }

        String coverEntryName = determineCoverEntryName(doc).replace('\\', '/');
        ensureFrontCoverPage(doc, coverEntryName, payload.getWidth(), payload.getHeight());
        byte[] xmlBytes = documentToBytes(doc);

        Path tempZip = Files.createTempFile("cbx_cover_", ".cbz");
        try (SevenZFile sevenZ = new SevenZFile(file);
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {

            SevenZArchiveEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                String normalizedEntry = entryName.replace('\\', '/');
                if (!isSafeEntryName(entryName)) {
                    log.warn("Skipping unsafe 7z entry name: {}", entryName);
                    drainSevenZEntry(sevenZ, buffer);
                    continue;
                }
                if (isComicInfoName(entryName) || normalizedEntry.equals(normalizedComicInfoName)) {
                    drainSevenZEntry(sevenZ, buffer);
                    continue;
                }
                if (normalizedEntry.equals(coverEntryName) ||
                        (legacyCoverEntryName != null && normalizedEntry.equals(legacyCoverEntryName))) {
                    drainSevenZEntry(sevenZ, buffer);
                    continue;
                }

                zos.putNextEntry(new ZipEntry(normalizedEntry));
                int read;
                while ((read = sevenZ.read(buffer, 0, buffer.length)) > 0) {
                    zos.write(buffer, 0, read);
                }
                zos.closeEntry();
            }

            writeZipEntry(zos, coverEntryName, payload.getBytes());
            writeZipEntry(zos, normalizedComicInfoName, xmlBytes);
        }

        Path target = file.toPath().resolveSibling(stripExtension(file.getName()) + ".cbz");
        atomicReplace(tempZip, target);
        Files.deleteIfExists(file.toPath());
        bookEntity.setFileName(target.getFileName().toString());
        refreshFileStats(bookEntity, target);
    }

    private void updateRarArchiveWithCover(BookEntity bookEntity, File file, CoverPayload payload) throws Exception {
        ComicInfoContext context = loadComicInfoFromRar(file);
        Document doc = context.document;
        String comicInfoName = context.entryName != null ? sanitizeEntryName(context.entryName) : null;
        if (comicInfoName == null || comicInfoName.isBlank()) {
            comicInfoName = "ComicInfo.xml";
        }

        String legacyCoverEntryName = sanitizeEntryName(extractFrontCoverImageName(doc));
        if (legacyCoverEntryName != null) {
            legacyCoverEntryName = legacyCoverEntryName.replace('\\', '/');
        }

        String coverEntryName = determineCoverEntryName(doc).replace('\\', '/');
        ensureFrontCoverPage(doc, coverEntryName, payload.getWidth(), payload.getHeight());
        byte[] xmlBytes = documentToBytes(doc);

        Path tempDir = Files.createTempDirectory("cbx_rar_cover_");
        try {
            extractRarToDirectory(file, tempDir);

            Path coverFile = tempDir.resolve(coverEntryName).normalize();
            if (!coverFile.startsWith(tempDir)) {
                throw new IllegalStateException("Cover path escapes extraction directory");
            }
            Files.createDirectories(coverFile.getParent());
            Files.write(coverFile, payload.getBytes());

            if (legacyCoverEntryName != null && !legacyCoverEntryName.equals(coverEntryName)) {
                Path legacyPath = tempDir.resolve(legacyCoverEntryName).normalize();
                if (legacyPath.startsWith(tempDir)) {
                    Files.deleteIfExists(legacyPath);
                }
            }

            Path comicInfoFile = tempDir.resolve(comicInfoName).normalize();
            if (!comicInfoFile.startsWith(tempDir)) {
                throw new IllegalStateException("ComicInfo path escapes extraction directory");
            }
            Files.createDirectories(comicInfoFile.getParent());
            Files.write(comicInfoFile, xmlBytes);

            String rarBin = System.getenv().getOrDefault("BOOKLORE_RAR_BIN", "rar");
            boolean rarAvailable = isRarAvailable(rarBin);
            if (rarAvailable) {
                Path target = file.toPath().toAbsolutePath().normalize();
                String rarExec = isSafeExecutable(rarBin) ? rarBin : "rar";
                ProcessBuilder pb = new ProcessBuilder(rarExec, "a", "-idq", "-ep1", "-ma5", target.toString(), ".");
                pb.directory(tempDir.toFile());
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    refreshFileStats(bookEntity, target);
                    return;
                }
                log.warn("RAR creation failed with exit code {}. Falling back to CBZ conversion for {}", exitCode, file.getName());
            }

            Path tempZip = Files.createTempFile("cbx_cover_", ".cbz");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
                try (Stream<Path> walk = Files.walk(tempDir)) {
                    walk.filter(Files::isRegularFile).forEach(path -> addFileToZip(zos, tempDir, path));
                }
            } catch (RuntimeException runtimeException) {
                if (runtimeException.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw runtimeException;
            }

            Path target = file.toPath().resolveSibling(stripExtension(file.getName()) + ".cbz");
            atomicReplace(tempZip, target);
            Files.deleteIfExists(file.toPath());
            bookEntity.setFileName(target.getFileName().toString());
            refreshFileStats(bookEntity, target);
        } finally {
            deleteDirectoryRecursively(tempDir);
        }
    }

    private ComicInfoContext loadComicInfoFromSevenZ(File file) throws Exception {
        try (SevenZFile sevenZ = new SevenZFile(file)) {
            SevenZArchiveEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getName() != null && isComicInfoName(entry.getName())) {
                    byte[] bytes = readSevenZEntryBytes(sevenZ, buffer);
                    if (bytes != null) {
                        try (InputStream is = new ByteArrayInputStream(bytes)) {
                            Document doc = buildSecureDocument(is);
                            return new ComicInfoContext(doc, entry.getName());
                        }
                    }
                } else {
                    drainSevenZEntry(sevenZ, buffer);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load ComicInfo.xml from {}: {}", file.getName(), e.getMessage());
        }
        return new ComicInfoContext(newEmptyComicInfo(), null);
    }

    private ComicInfoContext loadComicInfoFromRar(File file) throws Exception {
        try (Archive archive = new Archive(file)) {
            FileHeader header = findComicInfoHeader(archive);
            if (header != null) {
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    archive.extractFile(header, baos);
                    try (InputStream is = new ByteArrayInputStream(baos.toByteArray())) {
                        Document doc = buildSecureDocument(is);
                        return new ComicInfoContext(doc, header.getFileNameString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load ComicInfo.xml from {}: {}", file.getName(), e.getMessage());
        }
        return new ComicInfoContext(newEmptyComicInfo(), null);
    }

    private byte[] readSevenZEntryBytes(SevenZFile sevenZ, byte[] buffer) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int read;
        while ((read = sevenZ.read(buffer, 0, buffer.length)) > 0) {
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }

    private void drainSevenZEntry(SevenZFile sevenZ, byte[] buffer) {
        try {
            while (sevenZ.read(buffer, 0, buffer.length) > 0) {
                // no-op
            }
        } catch (Exception ignore) {
        }
    }

    private void extractRarToDirectory(File file, Path tempDir) throws Exception {
        try (Archive archive = new Archive(file)) {
            for (FileHeader fh : archive.getFileHeaders()) {
                String name = fh.getFileName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (!isSafeEntryName(name)) {
                    log.warn("Skipping unsafe RAR entry name: {}", name);
                    continue;
                }
                Path out = tempDir.resolve(name).normalize();
                if (!out.startsWith(tempDir)) {
                    log.warn("Skipping traversal entry outside tempDir: {}", name);
                    continue;
                }
                if (fh.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        archive.extractFile(fh, os);
                    }
                }
            }
        }
    }

    private String determineCoverEntryName(Document doc) {
        String existing = extractFrontCoverImageName(doc);
        if (existing != null) {
            String sanitized = sanitizeEntryName(existing);
            if (sanitized != null) {
                int idx = sanitized.lastIndexOf('/');
                String prefix = idx >= 0 ? sanitized.substring(0, idx + 1) : "";
                return prefix + "cover.jpg";
            }
        }
        return DEFAULT_COVER_ENTRY;
    }

    private String extractFrontCoverImageName(Document doc) {
        if (doc == null) {
            return null;
        }
        NodeList pagesNodes = doc.getElementsByTagName("Pages");
        for (int i = 0; i < pagesNodes.getLength(); i++) {
            Node node = pagesNodes.item(i);
            if (node instanceof Element pagesElement) {
                NodeList pageList = pagesElement.getElementsByTagName("Page");
                for (int j = 0; j < pageList.getLength(); j++) {
                    Node pageNode = pageList.item(j);
                    if (pageNode instanceof Element pageElement) {
                        String type = pageElement.getAttribute("Type");
                        if (type != null && type.equalsIgnoreCase("FrontCover")) {
                            String imageFile = pageElement.getAttribute("ImageFile");
                            if (imageFile != null && !imageFile.isBlank()) {
                                return imageFile.trim();
                            }
                            String image = pageElement.getAttribute("Image");
                            if (image != null && !image.isBlank()) {
                                return image.trim();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private String sanitizeEntryName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!isSafeEntryName(normalized)) {
            return null;
        }
        return normalized;
    }

    private void ensureFrontCoverPage(Document doc, String coverEntryName, int width, int height) {
        Element root = doc.getDocumentElement();
        if (root == null || !"ComicInfo".equals(root.getTagName())) {
            root = doc.createElement("ComicInfo");
            doc.appendChild(root);
        }

        Element pagesElement;
        NodeList pagesNodes = root.getElementsByTagName("Pages");
        if (pagesNodes.getLength() > 0) {
            pagesElement = (Element) pagesNodes.item(0);
        } else {
            pagesElement = doc.createElement("Pages");
            root.appendChild(pagesElement);
        }

        Element frontCover = null;
        NodeList pageNodes = pagesElement.getElementsByTagName("Page");
        for (int i = 0; i < pageNodes.getLength(); i++) {
            Node node = pageNodes.item(i);
            if (node instanceof Element pageElement) {
                String type = pageElement.getAttribute("Type");
                if (type != null && type.equalsIgnoreCase("FrontCover")) {
                    frontCover = pageElement;
                    break;
                }
            }
        }

        if (frontCover == null) {
            frontCover = doc.createElement("Page");
            if (pagesElement.hasChildNodes()) {
                pagesElement.insertBefore(frontCover, pagesElement.getFirstChild());
            } else {
                pagesElement.appendChild(frontCover);
            }
        }

        frontCover.setAttribute("Type", "FrontCover");
        frontCover.setAttribute("ImageFile", coverEntryName);
        frontCover.removeAttribute("Image");
        if (width > 0) {
            frontCover.setAttribute("ImageWidth", Integer.toString(width));
        } else {
            frontCover.removeAttribute("ImageWidth");
        }
        if (height > 0) {
            frontCover.setAttribute("ImageHeight", Integer.toString(height));
        } else {
            frontCover.removeAttribute("ImageHeight");
        }
    }

    private byte[] documentToBytes(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(baos));
        return baos.toByteArray();
    }

    private void writeZipEntry(ZipOutputStream zos, String entryName, byte[] data) {
        try {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(data);
            zos.closeEntry();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void addFileToZip(ZipOutputStream zos, Path baseDir, Path file) {
        String entryName = baseDir.relativize(file).toString().replace(File.separatorChar, '/');
        if (!isSafeEntryName(entryName)) {
            log.warn("Skipping unsafe entry during CBZ conversion: {}", entryName);
            return;
        }
        try (InputStream is = Files.newInputStream(file)) {
            zos.putNextEntry(new ZipEntry(entryName));
            is.transferTo(zos);
            zos.closeEntry();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void refreshFileStats(BookEntity bookEntity, Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            long sizeKb = Math.max(1L, Files.size(path) / 1024);
            bookEntity.setFileSizeKb(sizeKb);
            bookEntity.setCurrentHash(FileFingerprint.generateHash(path));
        } catch (Exception e) {
            log.warn("Failed to refresh file stats for {}: {}", path, e.getMessage());
        }
    }

    private void deleteDirectoryRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignore) {
                }
            });
        } catch (Exception ignore) {
        }
    }

    private ZipEntry findComicInfoEntry(ZipFile zipFile) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String n = entry.getName();
            if (isComicInfoName(n)) return entry;
        }
        return null;
    }

    private FileHeader findComicInfoHeader(Archive archive) {
        for (FileHeader fh : archive.getFileHeaders()) {
            String name = fh.getFileName();
            if (name != null && isComicInfoName(name)) return fh;
        }
        return null;
    }

    private Document buildSecureDocument(InputStream is) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(is);
    }

    private Document newEmptyComicInfo() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();
        doc.appendChild(doc.createElement("ComicInfo"));
        return doc;
    }

    private void setElement(Document doc, Element root, String tag, String value) {
        removeElement(root, tag);
        if (value != null && !value.isBlank()) {
            Element el = doc.createElement(tag);
            el.setTextContent(value);
            root.appendChild(el);
        }
    }

    private void removeElement(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        for (int i = nodes.getLength() - 1; i >= 0; i--) {
            root.removeChild(nodes.item(i));
        }
    }
    
    private void normalizeWhitespace(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            switch (child.getNodeType()) {
                case Node.TEXT_NODE -> {
                    Text text = (Text) child;
                    String content = text.getWholeText();
                    if (content == null || content.isBlank()) {
                        node.removeChild(text);
                    } else {
                        text.replaceWholeText(content.trim());
                    }
                }
                case Node.ELEMENT_NODE -> normalizeWhitespace(child);
                default -> {
                }
            }
        }
    }

    private void setDateElements(Document doc, Element root, LocalDate date) {
        if (date == null) {
            removeElement(root, "Year");
            removeElement(root, "Month");
            removeElement(root, "Day");
            return;
        }
        setElement(doc, root, "Year", Integer.toString(date.getYear()));
        setElement(doc, root, "Month", Integer.toString(date.getMonthValue()));
        setElement(doc, root, "Day", Integer.toString(date.getDayOfMonth()));
    }

    private String join(Set<String> set) {
        return (set == null || set.isEmpty()) ? null : String.join(", ", set);
    }

    private String formatFloat(Float val) {
        if (val == null) return null;
        if (val % 1 == 0) return Integer.toString(val.intValue());
        return val.toString();
    }

    private static boolean isComicInfoName(String name) {
        if (name == null) return false;
        String n = name.replace('\\', '/');
        if (n.endsWith("/")) return false;
        String lower = n.toLowerCase(Locale.ROOT);
        return lower.equals("comicinfo.xml") || lower.endsWith("/comicinfo.xml");
    }

    private static boolean isSafeEntryName(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.replace('\\', '/');
        if (n.startsWith("/")) return false; // absolute
        if (n.contains("../")) return false; // traversal
        if (n.contains("\0")) return false; // NUL
        return true;
    }

    private static void atomicReplace(Path temp, Path target) throws Exception {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // Fallback if filesystem doesn't support ATOMIC_MOVE
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean isRarAvailable(String rarBin) {
        try {
            String exec = isSafeExecutable(rarBin) ? rarBin : "rar";
            Process check = new ProcessBuilder(exec, "--help").redirectErrorStream(true).start();
            int exitCode = check.waitFor();
            return (exitCode == 0);
        } catch (Exception ex) {
            log.warn("RAR binary check failed: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Returns true if the provided executable reference is a simple name or sanitized absolute/relative path.
     * No spaces or shell meta chars; passed as argv to ProcessBuilder (no shell).
     */
    private boolean isSafeExecutable(String exec) {
        if (exec == null || exec.isBlank()) return false;
        // allow word chars, dot, slash, backslash, dash and underscore (no spaces or shell metas)
        return exec.matches("^[\\w./\\\\-]+$");
    }

    private static String stripExtension(String filename) {
        int i = filename.lastIndexOf('.');
        if (i > 0) return filename.substring(0, i);
        return filename;
    }

    @Override
    public BookFileType getSupportedBookType() {
        return BookFileType.CBX;
    }
}
