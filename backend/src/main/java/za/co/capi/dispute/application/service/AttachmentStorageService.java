package za.co.capi.dispute.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import za.co.capi.dispute.domain.model.Dispute;
import za.co.capi.dispute.domain.model.DisputeAttachment;
import za.co.capi.dispute.domain.repository.DisputeAttachmentRepository;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.presentation.dto.ApiDtos.AttachmentDto;
import za.co.capi.dispute.presentation.exception.ApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class AttachmentStorageService {

    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L; // 5 MB
    private static final int MAX_FILES_PER_DISPUTE = 5;
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/jpg"
    );
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".png", ".jpg", ".jpeg");

    private final DisputeAttachmentRepository attachmentRepository;
    private final AuditService auditService;
    private final Path rootStorageDir;

    public AttachmentStorageService(
            DisputeAttachmentRepository attachmentRepository,
            AuditService auditService,
            @Value("${app.storage.attachments-dir:/app/storage/attachments}") String attachmentsDir) {
        this.attachmentRepository = attachmentRepository;
        this.auditService = auditService;
        this.rootStorageDir = Paths.get(attachmentsDir).toAbsolutePath().normalize();
    }

    @Transactional
    public List<AttachmentDto> storeAttachments(
            Dispute dispute,
            List<MultipartFile> files,
            AppUserPrincipal actor,
            String ipAddress) {

        if (dispute.getStatus().isTerminal()) {
            throw ApiException.conflict("Cannot upload evidence files to a dispute in terminal status (" + dispute.getStatus() + ").");
        }

        if (files == null || files.isEmpty()) {
            return List.of();
        }

        List<MultipartFile> nonEmptyFiles = files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .toList();

        if (nonEmptyFiles.isEmpty()) {
            return List.of();
        }

        if (nonEmptyFiles.size() > MAX_FILES_PER_DISPUTE) {
            throw ApiException.badRequest("Maximum of 5 files allowed per upload.");
        }

        long existingCount = attachmentRepository.countByDisputeId(dispute.getId());
        if (existingCount + nonEmptyFiles.size() > MAX_FILES_PER_DISPUTE) {
            throw ApiException.badRequest("Maximum of 5 attachments allowed per dispute (currently has " + existingCount + ").");
        }

        List<AttachmentDto> savedDtos = new ArrayList<>();
        List<Path> writtenPaths = new ArrayList<>();
        Path disputeDir = rootStorageDir.resolve(dispute.getId().toString());
        try {
            Files.createDirectories(disputeDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize evidence storage directory", e);
        }

        boolean syncActive = TransactionSynchronizationManager.isSynchronizationActive();
        if (syncActive) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        deletePathsQuietly(writtenPaths);
                    }
                }
            });
        }

        try {
            for (MultipartFile file : nonEmptyFiles) {
                byte[] bytes = readAndValidateFile(file);
                String detectedMime = detectMimeTypeBySignature(bytes);
                String sha256 = computeSha256Hex(bytes);
                String safeName = sanitizeFilename(file.getOriginalFilename());
                String storedFileName = UUID.randomUUID() + "_" + safeName;
                Path targetPath = disputeDir.resolve(storedFileName).normalize();
                if (!targetPath.startsWith(disputeDir)) {
                    throw ApiException.badRequest("Invalid attachment file name.");
                }
                Files.write(targetPath, bytes);
                writtenPaths.add(targetPath);

                DisputeAttachment entity = new DisputeAttachment();
                entity.setDisputeId(dispute.getId());
                entity.setUploadedBy(actor.getId());
                entity.setFileName(safeName);
                entity.setFileSizeBytes(bytes.length);
                entity.setMimeType(detectedMime);
                entity.setStoragePath(targetPath.toString());
                entity.setChecksumSha256(sha256);
                entity.setCreatedAt(Instant.now());

                DisputeAttachment saved = attachmentRepository.save(entity);

                auditService.logEvent(
                        actor.getId(),
                        actor.getEmail(),
                        "INFO",
                        "ATTACHMENT_UPLOADED",
                        "DisputeAttachment",
                        saved.getId().toString(),
                        ipAddress,
                        Map.of(
                                "disputeId", dispute.getId().toString(),
                                "disputeReference", dispute.getDisputeReference(),
                                "fileName", safeName,
                                "fileSizeBytes", bytes.length,
                                "checksumSha256", sha256
                        )
                );

                savedDtos.add(toDto(saved));
            }
        } catch (IOException e) {
            if (!syncActive) {
                deletePathsQuietly(writtenPaths);
            }
            throw new RuntimeException("Failed to persist uploaded file", e);
        } catch (RuntimeException ex) {
            if (!syncActive) {
                deletePathsQuietly(writtenPaths);
            }
            throw ex;
        }

        return savedDtos;
    }

    @Transactional(readOnly = true)
    public DisputeAttachment getAttachmentForDispute(UUID disputeId, UUID attachmentId) {
        DisputeAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound("Attachment not found."));
        if (!attachment.getDisputeId().equals(disputeId)) {
            throw ApiException.notFound("Attachment does not belong to specified dispute.");
        }
        return attachment;
    }

    public Resource loadResource(DisputeAttachment attachment) {
        Path path = Paths.get(attachment.getStoragePath());
        if (!Files.exists(path)) {
            throw ApiException.notFound("Stored attachment file not found on disk.");
        }
        return new FileSystemResource(path);
    }

    public AttachmentDto toDto(DisputeAttachment a) {
        return new AttachmentDto(
                a.getId(),
                a.getDisputeId(),
                a.getUploadedBy(),
                a.getFileName(),
                a.getFileSizeBytes(),
                a.getMimeType(),
                a.getChecksumSha256(),
                a.getCreatedAt()
        );
    }

    private byte[] readAndValidateFile(MultipartFile file) throws IOException {
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw ApiException.badRequest("File '" + file.getOriginalFilename() + "' exceeds maximum size of 5MB.");
        }
        String name = sanitizeFilename(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        boolean validExt = ALLOWED_EXTENSIONS.stream().anyMatch(name::endsWith);
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase(Locale.ROOT) : "";
        boolean validMime = ALLOWED_MIME_TYPES.contains(contentType) || contentType.isBlank() || "application/octet-stream".equals(contentType);
        if (!validExt || !validMime) {
            throw ApiException.badRequest("Unsupported file format for '" + file.getOriginalFilename() + "'. Allowed formats: PDF, PNG, JPG.");
        }

        byte[] bytes = file.getBytes();
        if (bytes.length > MAX_FILE_SIZE_BYTES) {
            throw ApiException.badRequest("File '" + file.getOriginalFilename() + "' exceeds maximum size of 5MB.");
        }

        String detectedMime = detectMimeTypeBySignature(bytes);
        if (detectedMime == null) {
            throw ApiException.badRequest("Invalid or unrecognized file signature for '" + file.getOriginalFilename() + "'. Allowed signatures: PDF, PNG, JPEG.");
        }

        String expectedExtMime = expectedMimeForExtension(name);
        if (!detectedMime.equals(expectedExtMime)) {
            throw ApiException.badRequest("File content signature does not match file extension for '" + file.getOriginalFilename() + "'.");
        }

        if (!contentType.isBlank() && !"application/octet-stream".equals(contentType)) {
            String normalizedHeader = "image/jpg".equals(contentType) ? "image/jpeg" : contentType;
            if (!detectedMime.equals(normalizedHeader)) {
                throw ApiException.badRequest("Declared Content-Type does not match file signature for '" + file.getOriginalFilename() + "'.");
            }
        }

        return bytes;
    }

    private String expectedMimeForExtension(String lowerFileName) {
        if (lowerFileName.endsWith(".pdf")) return "application/pdf";
        if (lowerFileName.endsWith(".png")) return "image/png";
        return "image/jpeg";
    }

    private String detectMimeTypeBySignature(byte[] bytes) {
        if (bytes.length >= 5
                && bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46 && bytes[4] == 0x2D) {
            return "application/pdf";
        }
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    private void deletePathsQuietly(List<Path> paths) {
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // Best-effort cleanup of uncommitted attachment files
            }
        }
    }

    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "evidence.pdf";
        }
        return Paths.get(original).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static String computeSha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}

