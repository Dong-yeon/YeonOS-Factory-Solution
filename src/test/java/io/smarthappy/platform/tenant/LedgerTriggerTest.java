package io.smarthappy.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import io.smarthappy.support.PostgresTestBase;

/** 원장 규약 트리거 hb_forbid_update_delete (01 3.1): 추가만 되고 수정·삭제는 소유자도 막힌다 */
class LedgerTriggerTest extends PostgresTestBase {

    @Test
    void 원장은_추가만_되고_수정_삭제는_막힌다() throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("create table tmp_ledger (id int primary key, qty numeric, reversal_of_id int)");
                st.execute("create trigger tg before update or delete on tmp_ledger for each row execute function hb_forbid_update_delete()");
                st.execute("insert into tmp_ledger values (1, 10, null)");
                st.execute("insert into tmp_ledger values (2, -10, 1)"); // 역분개는 새 행
                st.execute("savepoint s");
                assertThatThrownBy(() -> st.execute("update tmp_ledger set qty = 99 where id = 1"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
                st.execute("rollback to savepoint s");
                assertThatThrownBy(() -> st.execute("delete from tmp_ledger where id = 1"))
                        .isInstanceOf(SQLException.class);
                st.execute("rollback to savepoint s");
                try (var rs = st.executeQuery("select sum(qty) from tmp_ledger")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            } finally {
                c.rollback();
            }
        }
    }
}
