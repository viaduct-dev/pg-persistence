package dev.viaduct.persistence.gradle

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConservativeSqlTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "ALTER TABLE groups ALTER COLUMN name TYPE integer USING name::integer;",
            "ALTER TABLE groups ALTER COLUMN name SET NOT NULL;",
            "ALTER TABLE groups ADD COLUMN active boolean NOT NULL;",
            "ALTER TABLE groups ADD COLUMN created_at timestamp DEFAULT now();",
            "ALTER TABLE groups ADD COLUMN sequence_number serial;",
            "ALTER TABLE groups ADD COLUMN sequence_number bigserial;",
            "ALTER TABLE groups ADD COLUMN sequence_number smallserial;",
            "ALTER TABLE groups ADD COLUMN sequence_number \"serial\";",
            "ALTER TABLE groups ADD COLUMN name required_name_domain;",
            "CREATE UNIQUE INDEX group_name ON groups(name);",
            "CREATE TABLE groups(id uuid); DELETE FROM persons;",
            "DO $$ BEGIN DROP TABLE groups; END $$;",
            "COMMENT ON TABLE groups IS '';",
            "ALTER TABLE groups RENAME TO other;",
            "CREATE TABLE groups(id uuid); /* unterminated",
        ],
    )
    fun `unfamiliar and potentially destructive SQL requires review`(sql: String) {
        assertFalse(ConservativeSql.isAllowed(sql))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "-- changeset test:1\nCREATE TABLE groups (id uuid);",
            "CREATE INDEX group_name ON groups(name);",
            "ALTER TABLE groups ADD COLUMN description text;",
            "ALTER TABLE groups ADD description text;",
            "COMMENT ON TABLE groups IS 'DROP DELETE TRUNCATE; are just text';",
            "/* DROP TABLE x; /* nested */ */ CREATE TABLE groups (id uuid);",
        ],
    )
    fun `recognized additive SQL ignores keywords in comments and literals`(sql: String) {
        assertTrue(ConservativeSql.isAllowed(sql))
    }
}
