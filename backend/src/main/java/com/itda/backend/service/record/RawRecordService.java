package com.itda.backend.service.record;

import java.io.IOException;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.exception.record.RawRecordEntryLimitException;
import com.itda.backend.exception.record.RawRecordNotFoundException;
import com.itda.backend.exception.record.RawRecordStorageException;
import com.itda.backend.exception.record.RawRecordValidationException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RawRecordService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "txt", "pdf", "docx");

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/csv", "application/vnd.ms-excel", "text/plain", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private static final Pattern FILENAME_DATE_KOREAN =
            Pattern.compile("(?:(?<year>20\\d{2})\\s*년\\s*)?(?<month>\\d{1,2})\\s*월\\s*(?<day>\\d{1,2})\\s*일");

    private static final Pattern FILENAME_DATE_WITH_YEAR =
            Pattern.compile("(?<year>20\\d{2})[-._]?(?<month>\\d{1,2})[-._]?(?<day>\\d{1,2})");

    private static final Pattern FILENAME_DATE_MONTH_DAY =
            Pattern.compile("(?<!\\d)(?<month>\\d{2})(?<day>\\d{2})(?!\\d)");

    private final RawRecordRepository rawRecordRepository;
    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalEntrySplitter journalEntrySplitter;
    private final ChildRepository childRepository;
    private final RawTextExtractor rawTextExtractor;
    private final RawRecordUploadGate uploadGate;
    private final RawRecordProperties properties;

    private String resolveInstitutionId(String userId) {
        return String.valueOf(userService.getOrganizationIdOf(userId));
    }

    public record ValidatedFile(String displayName, String extension, String contentType) {
    }

    @FunctionalInterface
    public interface BytesSource {
        byte[] read() throws IOException;
    }

    public ValidatedFile validate(String originalFilename, String contentType) {
        String displayFilename = sanitizeDisplayName(originalFilename);
        if (displayFilename.length() > RawRecord.MAX_TEXT_FIELD_LENGTH) {
            throw new RawRecordValidationException("file name is too long");
        }

        String extension = extractExtension(displayFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new RawRecordValidationException("unsupported file extension");
        }

        boolean docxAsOctetStream = "docx".equals(extension) && "application/octet-stream".equals(contentType);
        if (contentType == null || !(ALLOWED_CONTENT_TYPES.contains(contentType) || docxAsOctetStream)) {
            throw new RawRecordValidationException("unsupported content type");
        }
        return new ValidatedFile(displayFilename, extension, contentType);
    }

    public List<JournalEntrySplitter.SplitEntry> splitWithinLimits(String extension, BytesSource bytes) {
        List<JournalEntrySplitter.SplitEntry> entries;
        try (RawRecordUploadGate.Slot slot = uploadGate.acquire()) {
            entries = splitIntoEntries(extension, bytes.read());
        } catch (IOException e) {
            throw new RawRecordStorageException("failed to read raw file", e);
        }
        requireWithinEntryLimits(entries);
        return entries;
    }

    public RawRecord draft(Long organizationId, ValidatedFile file, String storedPath, long sizeBytes) {
        RawRecord rawRecord = new RawRecord(
                String.valueOf(organizationId),
                file.displayName(),
                storedPath,
                file.contentType(),
                sizeBytes,
                RawRecordStatus.PENDING);
        applyHintFromFilename(rawRecord, organizationId, file.displayName());
        return rawRecord;
    }

    public List<JournalEntrySplitter.SplitEntry> dated(
            RawRecord rawRecord, List<JournalEntrySplitter.SplitEntry> entries) {
        return withEntryDates(rawRecord, entries);
    }

    private List<JournalEntrySplitter.SplitEntry> splitIntoEntries(String extension, byte[] fileBytes) {
        Optional<String> text = rawTextExtractor.extract(extension, fileBytes);
        if (text.isEmpty()) {
            return List.of();
        }
        return withoutDocumentPreamble(
                extension, journalEntrySplitter.split(
                        "pdf".equals(extension) ? journalEntrySplitter.joinWrappedLines(text.get()) : text.get(),
                        properties.limits().maxEntriesPerFile() + 1));
    }

    private void requireWithinEntryLimits(List<JournalEntrySplitter.SplitEntry> entries) {
        int maxEntries = properties.limits().maxEntriesPerFile();
        if (entries.size() > maxEntries) {
            throw new RawRecordEntryLimitException(
                    "too many journal entries in file: " + entries.size() + " > " + maxEntries);
        }
        for (JournalEntrySplitter.SplitEntry entry : entries) {
            if (entry.content().length() > JournalEntry.MAX_CONTENT_LENGTH) {
                throw new RawRecordEntryLimitException("journal entry too long: " + entry.content().length()
                        + " > " + JournalEntry.MAX_CONTENT_LENGTH);
            }
        }
    }

    private List<JournalEntrySplitter.SplitEntry> withEntryDates(
            RawRecord rawRecord, List<JournalEntrySplitter.SplitEntry> entries) {
        if (entries.isEmpty()) {
            return entries;
        }
        LocalDate fallbackDate = resolveFallbackDate(rawRecord, entries);
        return entries.stream()
                .map(entry -> entry.entryDate() != null
                        ? entry
                        : new JournalEntrySplitter.SplitEntry(fallbackDate, entry.content()))
                .toList();
    }

    private static List<JournalEntrySplitter.SplitEntry> withoutDocumentPreamble(
            String extension, List<JournalEntrySplitter.SplitEntry> entries) {
        boolean hasDatedEntry = entries.stream().anyMatch(entry -> entry.entryDate() != null);
        if (!RawTextExtractor.DOCUMENT_EXTENSIONS.contains(extension) || !hasDatedEntry) {
            return entries;
        }
        List<JournalEntrySplitter.SplitEntry> dated =
                entries.stream().filter(entry -> entry.entryDate() != null).toList();
        if (dated.size() < entries.size()) {
            log.info("dropped {} preamble block(s) before the first date in a {} file", entries.size() - dated.size(), extension);
        }
        return dated;
    }

    private LocalDate resolveFallbackDate(RawRecord rawRecord, List<JournalEntrySplitter.SplitEntry> entries) {
        LocalDate firstDateInFile = entries.stream()
                .map(JournalEntrySplitter.SplitEntry::entryDate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (firstDateInFile != null) {
            return firstDateInFile;
        }

        LocalDate uploadedOn = rawRecord.getCreatedAt().toLocalDate();
        return parseDateFromFilename(rawRecord.getOriginalFilename(), uploadedOn).orElse(uploadedOn);
    }

    private static Optional<LocalDate> parseDateFromFilename(String filename, LocalDate uploadedOn) {
        for (Pattern format : List.of(FILENAME_DATE_KOREAN, FILENAME_DATE_WITH_YEAR, FILENAME_DATE_MONTH_DAY)) {
            Matcher matcher = format.matcher(filename);
            while (matcher.find()) {
                String year = matcher.namedGroups().containsKey("year") ? matcher.group("year") : null;
                Optional<LocalDate> parsed = toDate(year == null ? uploadedOn.getYear() : Integer.parseInt(year),
                        Integer.parseInt(matcher.group("month")), Integer.parseInt(matcher.group("day")));
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> toDate(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private void applyHintFromFilename(RawRecord rawRecord, Long organizationId, String displayFilename) {
        String normalizedFilename = Normalizer.normalize(displayFilename, Normalizer.Form.NFC);

        List<Child> hits = childRepository.findByOrganizationId(organizationId).stream()
                .filter(child -> normalizedFilename.contains(Normalizer.normalize(child.getName(), Normalizer.Form.NFC)))
                .toList();

        if (hits.size() > 1) {
            int longestNameLength = hits.stream().mapToInt(child -> child.getName().length()).max().orElse(0);
            hits = hits.stream().filter(child -> child.getName().length() == longestNameLength).toList();
        }

        if (hits.size() == 1) {
            Child child = hits.get(0);
            rawRecord.applyHint(child.getName(), child.getBirthdate());
        }
    }

    public RawRecord getById(Long id, String userId) {
        String institutionId = resolveInstitutionId(userId);
        RawRecord record = rawRecordRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new RawRecordNotFoundException(id));
        if (!record.getInstitutionId().equals(institutionId)) {
            log.warn("denied cross-institution access id={} requester={} owner={}",
                    id, institutionId, record.getInstitutionId());
            throw new RawRecordNotFoundException(id);
        }
        return record;
    }

    public List<RawRecord> getByInstitution(String userId) {
        return rawRecordRepository.findByInstitutionIdAndDeletedAtIsNull(resolveInstitutionId(userId));
    }

    private String sanitizeDisplayName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unknown";
        }
        String normalized = originalFilename.replace('\\', '/');
        String lastSegment = normalized.substring(normalized.lastIndexOf('/') + 1);
        return lastSegment.isBlank() ? "unknown" : lastSegment;
    }

    private String extractExtension(String displayFilename) {
        int dotIndex = displayFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == displayFilename.length() - 1) {
            return "";
        }
        return displayFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
