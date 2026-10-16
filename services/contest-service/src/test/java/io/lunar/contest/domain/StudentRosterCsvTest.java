package io.lunar.contest.domain;

import io.lunar.contest.domain.dto.RosterEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StudentRosterCsvTest {

    @Test
    void parsesRowsAndSkipsHeaderAndBlankLines() {
        String csv = "studentNo,username,displayName,initialPassword\n"
                + "20260001,student_a,张三,init-pass-1\n"
                + "\n"
                + "20260002,student_b,李四,init-pass-2\r\n";

        List<RosterEntry> entries = StudentRosterCsv.parse(csv);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).lineNumber()).isEqualTo(2);
        assertThat(entries.get(0).studentNo()).isEqualTo("20260001");
        assertThat(entries.get(0).displayName()).isEqualTo("张三");
        assertThat(entries.get(1).studentNo()).isEqualTo("20260002");
    }

    @Test
    void keepsCommasInsideQuotedFields() {
        List<RosterEntry> entries = StudentRosterCsv.parse("20260003,student_c,\"王五, 三班\",init-pass-3");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).displayName()).isEqualTo("王五, 三班");
    }

    @Test
    void unescapesDoubledQuotes() {
        List<RosterEntry> entries = StudentRosterCsv.parse("20260004,student_d,\"李\"\"四\"\"\",init-pass-4");

        assertThat(entries.get(0).displayName()).isEqualTo("李\"四\"");
    }

    @Test
    void missingColumnsBecomeNull() {
        List<RosterEntry> entries = StudentRosterCsv.parse("20260005,student_e");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).displayName()).isNull();
        assertThat(entries.get(0).initialPassword()).isNull();
    }

    @Test
    void blankContentYieldsNoEntries() {
        assertThat(StudentRosterCsv.parse("")).isEmpty();
        assertThat(StudentRosterCsv.parse(null)).isEmpty();
    }
}
