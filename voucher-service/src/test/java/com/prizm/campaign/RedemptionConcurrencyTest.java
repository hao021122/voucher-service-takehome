package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.exception.VoucherLimitReachException;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedemptionConcurrencyTest {

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @Test
    @DisplayName("Concurrent redemptions by one user never exceed the campaign limit")
    void redeem_parallelRequestsBySameUser_onlyLimitSucceed() throws Exception {
        String userId = "race-user";
        // Three free vouchers in campaign 1 (limit 2), all redeemed at the same instant.
        List<String> codes = List.of("RAYA-0002", "RAYA-0003", "RAYA-0006");

        ExecutorService pool = Executors.newFixedThreadPool(codes.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();

        List<Future<Object>> futures = codes.stream().map(code -> pool.<Object>submit(() -> {
            start.await();
            try {
                RedeemResponse res = voucherService.redeem(code, userId);
                if ("OK".equals(res.getResult())) ok.incrementAndGet();
            } catch (VoucherLimitReachException e) {
                limited.incrementAndGet();
            }
            return null;
        })).toList();

        start.countDown();
        for (Future<Object> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(2, ok.get());
        assertEquals(1, limited.get());
        assertEquals(2, redemptionRepository.countByUserIdAndCampaignId(userId, 1L));
    }
}
