package com.fang.spark.task;

/**
 * Created by fang on 16-12-29.
 */
public class DeleteSqlParser extends BaseSingleSqlParser{
    public DeleteSqlParser(String originalSql) {
        super(originalSql);
    }
    @Override
    protected void initializeSegments() {
        segments.add(new SqlSegment("(delete from)(.+)( where | ENDOFSQL)","[,]"));
        segments.add(new SqlSegment("(where)(.+)( ENDOFSQL)","(and|or)"));
    }
}