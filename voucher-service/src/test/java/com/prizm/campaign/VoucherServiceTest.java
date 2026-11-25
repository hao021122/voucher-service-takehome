package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.exception.VoucherLimitReachException;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Redemption;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.repository.VoucherRepository;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Each test runs in a transaction that is rolled back afterwards, so tests
 * never see each other's redemptions and can run in any order.
 *
 * Seed data used (data.sql):
 *   campaign 1 (limit 2): RAYA-0001/2/3/6 ACTIVE, RAYA-0004 REDEEMED, RAYA-0005 VOID
 *   campaign 2 (limit 2): MRDK-0001/2 ACTIVE
 *   user-99 has 2 redemptions in campaign 1 (RAYA-0004, and RAYA-0005 which was later voided)
 */
@SpringBootTest
@Transactional
class VoucherServiceTest {

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Redeems an active voucher successfully")
    void redeem_activeVoucher_returnsOk() {
        RedeemResponse res = voucherService.redeem("RAYA-0001", "user-1");
        assertEquals("OK", res.getResult());
    }

    @Test
    @DisplayName("Rejects a voucher that is already redeemed")
    void redeem_alreadyRedeemedVoucher_returnsFailed() {
        RedeemResponse res = voucherService.redeem("RAYA-0004", "user-2");
        assertEquals("FAILED", res.getResult());
    }

    @Test
    @DisplayName("Rejects an unknown voucher code")
    void redeem_unknownCode_returnsFailed() {
        RedeemResponse res = voucherService.redeem("NOPE-9999", "user-3");
        assertEquals("FAILED", res.getResult());
    }

    @Test
    @DisplayName("Allows redemption while user is below the campaign limit")
    void redeem_userBelowLimit_returnsOk() {
        // user-100 already has 1 of 2 allowed redemptions in campaign 1
        redemptionRepository.save(new Redemption(1L, 1L, "user-100", new Date()));

        RedeemResponse res = voucherService.redeem("RAYA-0002", "user-100");

        assertEquals("OK", res.getResult());
        assertEquals(2, redemptionRepository.countByUserIdAndCampaignId("user-100", 1L));
    }

    @Test
    @DisplayName("Rejects redemption when user has reached the campaign limit")
    void redeem_userAtLimit_throwsVoucherLimitReachException() {
        assertEquals("OK", voucherService.redeem("RAYA-0001", "user-200").getResult());
        assertEquals("OK", voucherService.redeem("RAYA-0002", "user-200").getResult());

        VoucherLimitReachException ex = assertThrows(VoucherLimitReachException.class,
                () -> voucherService.redeem("RAYA-0003", "user-200"));

        assertTrue(ex.getMessage().contains("(2)"), "message should state the limit: " + ex.getMessage());
    }

    @Test
    @DisplayName("Rejected redemption leaves stock, voucher and history unchanged")
    void redeem_userAtLimit_doesNotChangeStockOrVoucher() {
        int stockBefore = campaignRepository.findById(1L).orElseThrow().getRemainingStock();

        assertThrows(VoucherLimitReachException.class,
                () -> voucherService.redeem("RAYA-0001", "user-99"));

        assertEquals(stockBefore, campaignRepository.findById(1L).orElseThrow().getRemainingStock());
        Voucher voucher = voucherRepository.findByCode("RAYA-0001");
        assertEquals("ACTIVE", voucher.getStatus());
        assertNull(voucher.getRedeemedBy());
        assertEquals(2, redemptionRepository.countByUserIdAndCampaignId("user-99", 1L));
    }

    @Test
    @DisplayName("Voided redemptions still count toward the user limit")
    void redeem_userWithVoidedRedemption_countsTowardLimit() {
        // user-99's 2 redemptions include RAYA-0005, which has since been voided.
        // Current rule: voiding a voucher does not give the user their slot back.
        assertEquals("VOID", voucherRepository.findByCode("RAYA-0005").getStatus());

        assertThrows(VoucherLimitReachException.class,
                () -> voucherService.redeem("RAYA-0006", "user-99"));
    }

    @Test
    @DisplayName("Limit is per campaign: reaching it in one campaign doesn't block another")
    void redeem_userAtLimitInOtherCampaign_returnsOk() {
        // user-99 is at the limit in campaign 1 ...
        assertThrows(VoucherLimitReachException.class,
                () -> voucherService.redeem("RAYA-0001", "user-99"));

        // ... but has no redemptions in campaign 2
        RedeemResponse res = voucherService.redeem("MRDK-0001", "user-99");
        assertEquals("OK", res.getResult());
    }

    @Test
    @DisplayName("A campaign's own limit is enforced instead of the default")
    void redeem_campaignWithCustomLimit_enforcesCustomLimit() {
        createCampaign(100L, 1);
        createVoucher(1001L, 100L, "ONE-0001");
        createVoucher(1002L, 100L, "ONE-0002");

        assertEquals("OK", voucherService.redeem("ONE-0001", "user-300").getResult());

        VoucherLimitReachException ex = assertThrows(VoucherLimitReachException.class,
                () -> voucherService.redeem("ONE-0002", "user-300"));
        assertTrue(ex.getMessage().contains("(1)"), "message should state the limit: " + ex.getMessage());
    }

    @Test
    @DisplayName("Campaigns created without an explicit limit default to 2")
    void redeem_campaignWithoutExplicitLimit_defaultsToTwo() {
        // Insert the way existing rows were created: without the new column.
        jdbcTemplate.update("""
                INSERT INTO campaign (id, name, client_code, total_stock, remaining_stock, active)
                VALUES (101, 'Legacy Campaign', 'TEST', 10, 10, TRUE)""");
        createVoucher(1101L, 101L, "LGCY-0001");
        createVoucher(1102L, 101L, "LGCY-0002");
        createVoucher(1103L, 101L, "LGCY-0003");

        assertEquals(2, campaignRepository.findById(101L).orElseThrow().getMaxRedemptionsPerUser());
        // seeded campaigns got the default too
        assertEquals(2, campaignRepository.findById(1L).orElseThrow().getMaxRedemptionsPerUser());

        assertEquals("OK", voucherService.redeem("LGCY-0001", "user-400").getResult());
        assertEquals("OK", voucherService.redeem("LGCY-0002", "user-400").getResult());
        assertThrows(VoucherLimitReachException.class,
                () -> voucherService.redeem("LGCY-0003", "user-400"));
    }

    private void createCampaign(Long id, int maxPerUser) {
        Campaign c = new Campaign();
        c.setId(id);
        c.setName("Test campaign " + id);
        c.setClientCode("TEST");
        c.setTotalStock(10);
        c.setRemainingStock(10);
        c.setMaxRedemptionsPerUser(maxPerUser);
        c.setActive(true);
        campaignRepository.save(c);
    }

    private void createVoucher(Long id, Long campaignId, String code) {
        Voucher v = new Voucher();
        v.setId(id);
        v.setCampaignId(campaignId);
        v.setCode(code);
        v.setStatus("ACTIVE");
        voucherRepository.save(v);
    }
}
