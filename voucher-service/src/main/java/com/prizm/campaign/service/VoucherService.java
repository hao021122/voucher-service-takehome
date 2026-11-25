package com.prizm.campaign.service;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Redemption;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.repository.VoucherRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.prizm.campaign.exception.VoucherLimitReachException;

import java.util.Date;

@Service
public class VoucherService {

    private static final Logger log = LoggerFactory.getLogger(VoucherService.class);

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @Autowired
    private AuditClient auditClient;

    // One transaction for the whole check-then-write. Lock order is always
    // voucher row -> campaign row, so concurrent redemptions can't deadlock.
    @Transactional
    public RedeemResponse redeem(String code, String userId) {

        Voucher voucher = voucherRepository.findByCodeForUpdate(code);
        if (voucher == null) {
            return RedeemResponse.fail("Voucher not found");
        }

        if ("REDEEMED".equals(voucher.getStatus())) {
            return RedeemResponse.fail("Voucher already redeemed");
        }

        if ("VOID".equals(voucher.getStatus())) {
            return RedeemResponse.fail("Voucher is void");
        }

        // Held until commit: a second request for the same campaign waits here,
        // then sees this request's redemption row and decremented stock.
        Campaign campaign = campaignRepository.findByIdForUpdate(voucher.getCampaignId()).get();
        long used = redemptionRepository.countByUserIdAndCampaignId(userId, campaign.getId());

        if (!campaign.isActive()) {
            return RedeemResponse.fail("Campaign is not active");
        }

        if (used >= campaign.getMaxRedemptionsPerUser()) {
            throw new VoucherLimitReachException("User has reached the redemption limit for this campaign (" + campaign.getMaxRedemptionsPerUser() + ")");
        }

        if (campaign.getRemainingStock() <= 0) {
            return RedeemResponse.fail("Campaign out of stock");
        }

        voucher.setStatus("REDEEMED");
        voucher.setRedeemedBy(userId);
        voucher.setRedeemedAt(new Date());
        voucherRepository.save(voucher);

        campaign.setRemainingStock(campaign.getRemainingStock() - 1);
        campaignRepository.save(campaign);

        Redemption redemption = new Redemption(
                voucher.getId(), campaign.getId(), userId, new Date());
        redemptionRepository.save(redemption);

        // Send the audit event only after commit: no slow HTTP call while the
        // campaign lock is held, and no audit event for a rolled-back redemption.
        String clientCode = campaign.getClientCode();
        String voucherCode = voucher.getCode();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    auditClient.recordRedemption(clientCode, voucherCode, userId);
                } catch (Exception e) {
                    log.error("audit call failed");
                }
            }
        });

        return RedeemResponse.ok(voucher.getCode(), campaign.getRemainingStock());
    }

    public RedeemResponse voidVoucher(String code) {
        Voucher voucher = voucherRepository.findByCode(code);
        if (voucher == null) {
            return RedeemResponse.fail("Voucher not found");
        }
        voucher.setStatus("VOID");
        voucherRepository.save(voucher);
        return RedeemResponse.ok(voucher.getCode(), null);
    }
}
