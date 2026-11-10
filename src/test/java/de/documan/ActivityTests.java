package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import de.documan.domain.ActivityKind;
import de.documan.domain.Document;
import de.documan.repo.ActivityRepository;
import de.documan.repo.ActivityRepository.UserActivity;
import de.documan.service.DashboardService;

class ActivityTests extends IntegrationTest {

    @Autowired
    ActivityRepository activities;

    private Map<String, UserActivity> totals() {
        return activities.overview().stream().collect(Collectors.toMap(UserActivity::username, Function.identity()));
    }

    private void bulk(RequestPostProcessor person, String action, String value, Document... selected) throws Exception {
        MockHttpServletRequestBuilder request = post("/stapel").with(person).with(csrf()).param("action", action)
                .param("value", value);
        for (Document document : selected) {
            request.param("ids", String.valueOf(document.id()));
        }
        mvc.perform(request).andReturn();
    }











    @Test
    void countsAddUpPerUser() {
        activities.record("anna", ActivityKind.FILED, 3);
        activities.record("anna", ActivityKind.FILED, 2);
        activities.record("chef", ActivityKind.FILED, 1);
        assertThat(activities.inboxCounts()).containsOnly(Map.entry("anna", 5L), Map.entry("chef", 1L));
    }

    @Test
    void mostRecentlyActiveUserComesFirst() {
        activities.record("anna", ActivityKind.EDITED, 1);
        jdbc.sql("UPDATE activity SET at = at - interval '1 hour'").update();
        activities.record("chef", ActivityKind.EDITED, 1);
        assertThat(activities.overview()).extracting(UserActivity::username).containsExactly("chef", "anna");
    }

    @Test
    void inboxPieLinksNowhere() {
        assertThat(DashboardService.buildPie(Map.of("anna", 3L), false).slices().getFirst().url()).isNull();
    }




}
