package io.lunar.contest.domain;

import io.lunar.contest.domain.dto.RosterEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 名单解析（docs/exam-process.md §9）：列顺序 studentNo,username,displayName,initialPassword。
 *
 * 约定：首个非空行若以 studentNo 开头视为表头并跳过；空行跳过；字段可用双引号包裹
 * （内部逗号保留、"" 表示一个字面双引号）；各字段首尾空白会被裁剪。
 *
 * 列数不足的行**不在这里报错**：缺失字段以 null 返回，由导入流程逐行给出失败原因——
 * 这样"部分行失败不回滚"的语义对格式错误同样成立。
 */
public final class StudentRosterCsv {

    private static final String HEADER_FIRST_COLUMN = "studentNo";

    private StudentRosterCsv() {
    }

    public static List<RosterEntry> parse(String csvContent) {
        List<RosterEntry> entries = new ArrayList<>();
        if (csvContent == null || csvContent.isBlank()) {
            return entries;
        }
        String[] lines = csvContent.split("\r\n|\r|\n");
        boolean headerPending = true;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            List<String> columns = splitLine(line);
            if (headerPending) {
                headerPending = false;
                if (!columns.isEmpty() && HEADER_FIRST_COLUMN.equalsIgnoreCase(columns.get(0))) {
                    continue;
                }
            }
            entries.add(new RosterEntry(index + 1, columnAt(columns, 0), columnAt(columns, 1),
                    columnAt(columns, 2), columnAt(columns, 3), columnAt(columns, 4)));
        }
        return entries;
    }

    /** 逗号分隔 + 双引号包裹；返回的字段已裁剪首尾空白。 */
    private static List<String> splitLine(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (inQuotes) {
                if (character != '"') {
                    current.append(character);
                } else if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    inQuotes = false;
                }
            } else if (character == '"') {
                inQuotes = true;
            } else if (character == ',') {
                columns.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        columns.add(current.toString().trim());
        return columns;
    }

    private static String columnAt(List<String> columns, int index) {
        if (index >= columns.size()) {
            return null;
        }
        String value = columns.get(index);
        return value == null || value.isEmpty() ? null : value;
    }
}
