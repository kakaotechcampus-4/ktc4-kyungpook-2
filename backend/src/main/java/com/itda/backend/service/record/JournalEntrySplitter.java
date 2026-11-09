package com.itda.backend.service.record;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.Year;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class JournalEntrySplitter {

    private static final Pattern DATE_HEADER = Pattern.compile(
            "^(?:(\\d{4})[-.](\\d{1,2})[-.](\\d{1,2})|(\\d{1,2})/(\\d{1,2}))"
                    + "(\\s*(?:\\([월화수목금토일]\\)|[월화수목금토일]요일)?\\s*[:\\-]?\\s*)(.*)$");

    private static final Pattern QUANTITY_WORD =
            Pattern.compile("^(정도|이상|이하|미만|가량|쯤|만큼|남짓|수준)");

    private static final int AMBIGUOUS_DAY_MAX = 12;

    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    public record SplitEntry(LocalDate entryDate, String content) {
    }

    public List<SplitEntry> split(String text) {
        return split(text, Integer.MAX_VALUE);
    }

    public List<SplitEntry> split(String text, int maxEntries) {
        List<SplitEntry> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        Iterator<String> lines = LINE_BREAK.splitAsStream(text).iterator();

        LocalDate currentDate = null;
        StringBuilder buffer = new StringBuilder();

        while (lines.hasNext() && result.size() <= maxEntries) {
            String line = lines.next();
            Matcher m = dateHeaderOf(line);
            if (m != null) {
                flush(result, currentDate, buffer);
                currentDate = parseDate(m);
                buffer = new StringBuilder(m.group(7));
            } else if (!line.isBlank()) {
                if (!buffer.isEmpty()) {
                    buffer.append('\n');
                }
                buffer.append(line.strip());
            }
        }
        flush(result, currentDate, buffer);

        if (result.isEmpty() && !text.isBlank()) {
            result.add(new SplitEntry(null, text.strip()));
        }
        return result;
    }

    public String joinWrappedLines(String text) {
        String[] lines = text.split("\\R", -1);
        StringBuilder joined = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            boolean wrapped = !lines[i].isBlank() && !lines[i - 1].isBlank()
                    && dateHeaderOf(lines[i]) == null && !isDateOnly(lines[i - 1]);
            joined.append(wrapped ? "" : "\n").append(lines[i]);
        }
        return joined.toString();
    }

    private boolean isDateOnly(String line) {
        Matcher m = dateHeaderOf(line);
        return m != null && m.group(7).isBlank();
    }

    private Matcher dateHeaderOf(String line) {
        Matcher m = DATE_HEADER.matcher(line.strip());
        if (!m.matches()) {
            return null;
        }
        if (parseDate(m) == null) {
            return null;
        }
        return looksLikeFraction(m) ? null : m;
    }

    private boolean looksLikeFraction(Matcher m) {
        if (m.group(4) == null) {
            return false;
        }
        int month = Integer.parseInt(m.group(4));
        int day = Integer.parseInt(m.group(5));
        if (day > AMBIGUOUS_DAY_MAX || month >= day) {
            return false;
        }
        boolean particleAttached = m.group(6).isEmpty() && !m.group(7).isEmpty();
        return particleAttached || QUANTITY_WORD.matcher(m.group(7)).find();
    }

    private void flush(List<SplitEntry> result, LocalDate date, StringBuilder buffer) {
        String content = buffer.toString().strip();
        if (!content.isEmpty()) {
            result.add(new SplitEntry(date, content));
        }
    }

    private LocalDate parseDate(Matcher m) {
        try {
            if (m.group(1) != null) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            MonthDay md = MonthDay.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
            return md.atYear(Year.now().getValue());
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }
}
