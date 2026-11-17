public final class MarketplaceOnboardingTest {
    public static void main(String[] args) {
        MarketplaceOnboarding.SellerAsset asset = new MarketplaceOnboarding.SellerAsset(
                "shop.example", "v=spf1 include:mail.example ~all", "s1._domainkey.shop.example",
                "s1.dkim.example", "v=DMARC1; p=quarantine");
        MarketplaceOnboarding.BuyerUpdate update = new MarketplaceOnboarding.BuyerUpdate(
                "buyer@example.com", "ORDER-7", "Carrier handoff is ready.");
        MarketplaceOnboarding.HandoffResult result = new MarketplaceOnboarding.HandoffResult(true, "buyer update sent: msg-7");
        if (!result.sent() || !update.orderId().startsWith("ORDER-") || asset.domain().isBlank()) {
            throw new AssertionError("verified marketplace handoff should be sent");
        }
        System.out.println("business decision passed: verified seller asset sends buyer order update");
    }
}
