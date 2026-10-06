package io.lunar.problem.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** problem_schema.testcases 测试点（判题/管理视图，考生 API 不返回）。 */
@TableName("problem_schema.testcases")
public class TestcaseEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String versionId;

    private String name;

    private String inputContent;

    private String expectedOutput;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInputContent() { return inputContent; }
    public void setInputContent(String inputContent) { this.inputContent = inputContent; }
    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }
}