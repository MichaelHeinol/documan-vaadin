package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import de.documan.domain.Document;

/** Vorschau, Vorschaubild und Download liegen außerhalb von Vaadin und brauchen eine eigene Freigabe. */
class FileAccessTests extends IntegrationTest {

    private Document withThumbnail() throws Exception {
        Document document = document("Befund").original("originals/befund.pdf").stored();
        Path thumbnail = mediaRoot.resolve("thumbnails/befund.jpg");
        Files.createDirectories(thumbnail.getParent());
        Files.write(thumbnail, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
        jdbc.sql("UPDATE document SET thumbnail = 'thumbnails/befund.jpg' WHERE id = ?").param(document.id()).update();
        return reload(document);
    }

    private int status(String url, boolean signedIn) throws Exception {
        var request = get(url);
        if (signedIn) {
            request.with(ANNA);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void signedInUsersGetPreviewThumbnailAndDownload() throws Exception {
        long id = withThumbnail().id();
        assertThat(status("/dokumente/" + id + "/vorschau", true)).isEqualTo(200);
        assertThat(status("/dokumente/" + id + "/bild", true)).isEqualTo(200);
        assertThat(status("/dokumente/" + id + "/download/original", true)).isEqualTo(200);
    }

    @Test
    void withoutSignInTheFilesStayClosed() throws Exception {
        long id = withThumbnail().id();
        for (String url : new String[] {"/dokumente/" + id + "/vorschau", "/dokumente/" + id + "/bild",
                "/dokumente/" + id + "/download/original"}) {
            assertThat(status(url, false)).as(url).isNotEqualTo(200);
        }
    }
}
