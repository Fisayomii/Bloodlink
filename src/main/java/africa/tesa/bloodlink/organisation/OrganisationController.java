package africa.tesa.bloodlink.organisation;

import africa.tesa.bloodlink.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /organisations: blood banks and hospitals, for search filters and display names. Any signed-in user.
 * Safe (reads only).
 */
@RestController
public class OrganisationController {

    private static final int MAX_AREA_LENGTH = 80;

    private final JdbcClient jdbc;

    public OrganisationController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/organisations")
    OrganisationList list(@RequestParam(required = false) OrganisationType type,
                          @RequestParam(required = false) String area) {
        if (area != null && area.length() > MAX_AREA_LENGTH) {
            throw ApiException.validation("area", "At most " + MAX_AREA_LENGTH + " characters.");
        }
        // Each filter applies only when given: "? IS NULL OR ..." keeps one query for every combination.
        List<OrganisationView> items = jdbc.sql("""
                SELECT id, name, type, area
                  FROM organisation
                 WHERE (CAST(:type AS text) IS NULL OR type = :type)
                   AND (CAST(:area AS text) IS NULL OR lower(area) = lower(:area))
                 ORDER BY name""")
            .param("type", type == null ? null : type.name())
            .param("area", area)
            .query((rs, row) -> new OrganisationView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                OrganisationType.valueOf(rs.getString("type")),
                rs.getString("area")))
            .list();
        return new OrganisationList(items);
    }

    /** Contract: { "items": [Organisation] }. */
    public record OrganisationList(List<OrganisationView> items) {
    }
}
