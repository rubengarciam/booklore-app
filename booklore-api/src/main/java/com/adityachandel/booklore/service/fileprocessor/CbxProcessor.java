package com.adityachandel.booklore.service.fileprocessor;

import com.adityachandel.booklore.mapper.BookMapper;
import com.adityachandel.booklore.model.dto.BookMetadata;
import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.BookMetadataEntity;
import com.adityachandel.booklore.model.enums.BookFileType;
import com.adityachandel.booklore.repository.BookAdditionalFileRepository;
import com.adityachandel.booklore.repository.BookMetadataRepository;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.service.BookCreatorService;
import com.adityachandel.booklore.service.metadata.extractor.CbxMetadataExtractor;
import com.adityachandel.booklore.service.metadata.MetadataMatchService;
import com.adityachandel.booklore.util.FileService;
import com.adityachandel.booklore.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.util.*;

import static com.adityachandel.booklore.util.FileService.truncate;


@Slf4j
@Service
public class CbxProcessor extends AbstractFileProcessor implements BookFileProcessor {

    private final BookMetadataRepository bookMetadataRepository;
    private final CbxMetadataExtractor cbxMetadataExtractor;

    public CbxProcessor(BookRepository bookRepository,
                        BookAdditionalFileRepository bookAdditionalFileRepository,
                        BookCreatorService bookCreatorService,
                        BookMapper bookMapper,
                        FileService fileService,
                        BookMetadataRepository bookMetadataRepository,
                        MetadataMatchService metadataMatchService, 
                        CbxMetadataExtractor cbxMetadataExtractor) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService);
        this.bookMetadataRepository = bookMetadataRepository;
         this.cbxMetadataExtractor = cbxMetadataExtractor;
    }

    @Override
    public BookEntity processNewFile(LibraryFile libraryFile) {
        BookEntity bookEntity = bookCreatorService.createShellBook(libraryFile, BookFileType.CBX);
        if (generateCover(bookEntity)) {
            fileService.setBookCoverPath(bookEntity.getMetadata());
        }
        
        extractAndSetMetadata(bookEntity);
        return bookEntity;
    }

    @Override
    public boolean generateCover(BookEntity bookEntity) {
        File file = new File(FileUtils.getBookFullPath(bookEntity));
        try {
            Optional<BufferedImage> imageOptional = extractImagesFromArchive(file);
            if (imageOptional.isPresent()) {
                boolean saved = fileService.saveCoverImages(imageOptional.get(), bookEntity.getId());
                if (saved) {
                    bookEntity.getMetadata().setCoverUpdatedOn(Instant.now());
                    bookMetadataRepository.save(bookEntity.getMetadata());
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("Error generating cover for '{}': {}", bookEntity.getFileName(), e.getMessage());
        }
        return false;
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.CBX);
    }

    private Optional<BufferedImage> extractImagesFromArchive(File file) {
        try {
            byte[] coverBytes = cbxMetadataExtractor.extractCover(file);
            if (coverBytes != null && coverBytes.length > 0) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(coverBytes)) {
                    BufferedImage image = ImageIO.read(bais);
                    if (image != null) {
                        return Optional.of(image);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to decode cover extracted from {}: {}", file.getName(), e.getMessage());
        }

        return Optional.empty();
    }

    private void extractAndSetMetadata(BookEntity bookEntity) {
        try {
            BookMetadata extracted = cbxMetadataExtractor.extractMetadata(new File(FileUtils.getBookFullPath(bookEntity)));
            if (extracted == null) {
                // Fallback to filename-derived title
                setMetadata(bookEntity);
                return;
            }

            BookMetadataEntity metadata = bookEntity.getMetadata();
            metadata.setTitle(truncate(extracted.getTitle(), 1000));
            metadata.setDescription(truncate(extracted.getDescription(), 5000));
            metadata.setPublisher(truncate(extracted.getPublisher(), 1000));
            metadata.setPublishedDate(extracted.getPublishedDate());
            metadata.setSeriesName(truncate(extracted.getSeriesName(), 1000));
            metadata.setSeriesNumber(extracted.getSeriesNumber());
            metadata.setSeriesTotal(extracted.getSeriesTotal());
            metadata.setPageCount(extracted.getPageCount());
            metadata.setLanguage(truncate(extracted.getLanguage(), 1000));

            if (extracted.getAuthors() != null) {
                bookCreatorService.addAuthorsToBook(extracted.getAuthors(), bookEntity);
            }
            if (extracted.getCategories() != null) {
                bookCreatorService.addCategoriesToBook(extracted.getCategories(), bookEntity);
            }
        } catch (Exception e) {
            log.warn("Failed to extract ComicInfo metadata for '{}': {}", bookEntity.getFileName(), e.getMessage());
            // Fallback to filename-derived title
            setMetadata(bookEntity);
        }
    }    

    private void setMetadata(BookEntity bookEntity) {
        String baseName = new File(bookEntity.getFileName()).getName();
        String title = baseName
                .replaceAll("(?i)\\.cb[rz7]$", "")
                .replaceAll("[_\\-]", " ")
                .trim();
        bookEntity.getMetadata().setTitle(truncate(title, 1000));
    }
}
