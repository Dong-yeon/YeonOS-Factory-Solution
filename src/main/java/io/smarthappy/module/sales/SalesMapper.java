package io.smarthappy.module.sales;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.sales.SalesDtos.CompareRow;
import io.smarthappy.module.sales.SalesDtos.CustOption;
import io.smarthappy.module.sales.SalesDtos.CustRef;
import io.smarthappy.module.sales.SalesDtos.FcstLineRow;
import io.smarthappy.module.sales.SalesDtos.FcstLineValue;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.module.sales.SalesDtos.LineRow;
import io.smarthappy.module.sales.SalesDtos.LineValue;
import io.smarthappy.module.sales.SalesDtos.OrderRow;
import io.smarthappy.module.sales.SalesDtos.OrderValue;
import io.smarthappy.module.sales.SalesDtos.RevRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface SalesMapper {

    // ---------- 선택지·조회 ----------

    /** 판매 거래처(SALES·BOTH). activeOnly = 사용 중만 */
    List<CustOption> findSalesCusts(@Param("activeOnly") boolean activeOnly);

    /** 코드로 거래처 — 판매 여부·사용 여부와 무관(검증에서 구분) */
    CustRef findCust(@Param("custCd") String custCd);

    ItemOption findItem(@Param("itemCd") String itemCd);

    List<ItemOption> searchItems(@Param("q") String q, @Param("limit") int limit);

    // ---------- 수주 ----------

    List<OrderRow> findOrders(@Param("q") OrderService.OrderFilter q, @Param("limit") int limit);

    /** 목록 조건에 맞는 수주의 열린 행 중 납기 조건에 맞는 행 수 */
    long countDueLines(@Param("q") OrderService.OrderFilter q);

    List<OrderRow> findOrderPage(@Param("q") OrderService.OrderFilter q, @Param("page") PageQuery page);

    long countOrders(@Param("q") OrderService.OrderFilter q);

    OrderRow findOrder(@Param("id") long id);

    /** 행 변경 전에 머리를 잠근다 — 상태 계산·행 번호가 동시 변경과 섞이지 않게 */
    String lockOrder(@Param("id") long id);

    Long findOrderIdByPo(@Param("custId") long custId, @Param("custPoNo") String custPoNo);

    long insertOrder(@Param("orderNo") String orderNo, @Param("f") OrderValue f, @Param("by") String by);

    int updateOrder(@Param("id") long id, @Param("f") OrderValue f, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 행에서 머리 상태 계산 — 바뀔 때만 UPDATE */
    int refreshOrderStatus(@Param("id") long id, @Param("by") String by);

    /** 출하가 있는 열린 행 수 — 수주 취소 전 확인(docs/25 4.22) */
    long countShippedOpenLines(@Param("orderId") long orderId);

    List<LineRow> findLines(@Param("orderId") long orderId);

    LineRow findLine(@Param("id") long id);

    LineRow findLineByNo(@Param("orderId") long orderId, @Param("lineNo") int lineNo);

    int nextLineNo(@Param("orderId") long orderId);

    long insertLine(@Param("orderId") long orderId, @Param("f") LineValue f, @Param("by") String by);

    int updateLine(@Param("id") long id, @Param("f") LineValue f, @Param("rowVer") int rowVer, @Param("by") String by);

    int closeLine(@Param("id") long id, @Param("status") String status, @Param("reason") String reason,
            @Param("rowVer") Integer rowVer, @Param("by") String by);

    /** 열린 행 전부 취소(수주 취소) */
    int cancelOpenLines(@Param("orderId") long orderId, @Param("reason") String reason, @Param("by") String by);

    long countLines();

    // ---------- 내시 ----------

    List<RevRow> findRevs(@Param("custId") long custId);

    List<RevRow> findRevPage(@Param("custId") long custId, @Param("page") PageQuery page);

    long countRevs(@Param("custId") long custId);

    RevRow findRev(@Param("id") long id);

    /** 같은 거래처 수신을 트랜잭션 끝까지 줄 세운다 */
    String lockCustRev(@Param("custId") long custId);

    /** 다음 버전 번호 — lockCustRev 뒤에 부른다 */
    int nextRevNo(@Param("custId") long custId);

    long insertRev(@Param("custId") long custId, @Param("revNo") int revNo, @Param("source") String source,
            @Param("jobId") Long jobId, @Param("remark") String remark, @Param("by") String by);

    int insertFcstLines(@Param("revId") long revId, @Param("lines") List<FcstLineValue> lines);

    List<FcstLineRow> findFcstLines(@Param("revId") long revId);

    List<CompareRow> compare(@Param("fromRevId") long fromRevId, @Param("toRevId") long toRevId);

    long countFcstLines();
}
