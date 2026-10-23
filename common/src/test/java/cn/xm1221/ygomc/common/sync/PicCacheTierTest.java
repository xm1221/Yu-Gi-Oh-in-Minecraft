package cn.xm1221.ygomc.common.sync;

import cn.xm1221.ygomc.common.data.CardImageDb;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PicCacheTierTest {

    @Test
    void tiersMatchCardImageDb() throws Exception {
        assertEquals(0, CardImageDb.TIER_FULL);
        assertEquals(1, CardImageDb.TIER_ICON);
        assertEquals(".full.jpg", PicCache.suffix(CardImageDb.TIER_FULL));
        assertEquals(".icon.jpg", PicCache.suffix(CardImageDb.TIER_ICON));
        assertNull(PicCache.suffix(2));

        BufferedImage src = new BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB);
        BufferedImage full = PicCache.scale(src, PicCache.FULL_WIDTH, PicCache.FULL_HEIGHT);
        BufferedImage icon = PicCache.scale(src, PicCache.ICON_WIDTH, PicCache.ICON_HEIGHT);
        assertEquals(200, full.getWidth());
        assertEquals(290, full.getHeight());
        assertEquals(64, icon.getWidth());
        assertEquals(93, icon.getHeight());

        SyncPins pins = SyncPins.loadDefault();
        assertTrue(pins.pics.urls.get(0).startsWith("https://images.ygoprodeck.com/images/cards/"));
        assertTrue(pins.pics.urls.get(1).startsWith("https://cdn.233.momobako.com/ygopro/pics/"));
        assertTrue(pins.pics.urls.get(0).contains("{code}"));
    }
}
