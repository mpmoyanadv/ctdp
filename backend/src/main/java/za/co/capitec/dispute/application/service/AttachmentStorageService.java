package za.co.capitec.dispute.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import za.co.capitec.dispute.domain.model.Dispute;
import za.co.capitec.dispute.domain.model.DisputeAttachment;
import za.co.capitec.dispute.domain.repository.DisputeAttachmentRepository;
import za.co.capitec.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capitec.dispute.presentation.dto.ApiDtos.AttachmentDto;
import za.co.capitec.dispute.presentation.exception.ApiException;

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
        Path disputeDir = rootStorageDir.resolve(dispute.getId().toString());
        try {
            Files.createDirectories(disputeDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize evidence storage directory", e);
        }

        for (MultipartFile file : nonEmptyFiles) {
            validateFile(file);
            try {
                byte[] bytes = file.getBytes();
                String sha256 = computeSha256Hex(bytes);
                String safeName = sanitizeFilename(file.getOriginalFilename());
                String storedFileName = UUID.randomUUID() + "_" + safeName;
                Path targetPath = disputeDir.resolve(storedFileName).normalize();
                if (!targetPath.startsWith(disputeDir)) {
                    throw ApiException.badRequest("Invalid attachment file name.");
                }
                Files.write(targetPath, bytes);

                DisputeAttachment entity = new DisputeAttachment();
                entity.setDisputeId(dispute.getId());
                entity.setUploadedBy(actor.getId());
                entity.setFileName(safeName);
                entity.setFileSizeBytes(bytes.length);
                entity.setMimeType(normalizeMimeType(file.getContentType(), safeName));
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
            } catch (IOException e) {
                throw new RuntimeException("Failed to persist uploaded file", e);
            }
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

    private void validateFile(MultipartFile file) {
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
    }

    private String normalizeMimeType(String contentType, String fileName) {
        if (contentType != null && ALLOWED_MIME_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            return "image/jpg".equalsIgnoreCase(contentType) ? "image/jpeg" : contentType.toLowerCase(Locale.ROOT);
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        return "image/jpeg";
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

