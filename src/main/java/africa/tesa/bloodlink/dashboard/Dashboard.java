package africa.tesa.bloodlink.dashboard;

import africa.tesa.bloodlink.organisation.OrganisationView;
import africa.tesa.bloodlink.unit.UnitView;
import java.time.Instant;
import java.util.List;

/**
 * Contract: Dashboard. bank is null when the administrator views every bank. The three windows are returned with the
 * figures, so the screen can say what "running low", "ending soon" and "expiring soon" mean.
 */
public record Dashboard(
    Instant computedAt,
    OrganisationView bank,
    int lowStockThreshold,
    int holdsEndingWithinMinutes,
    int expiringWithinDays,
    List<GroupCount> availableByGroup,
    List<GroupCount> lowGroups,
    OpenRequests openRequests,
    List<HoldEndingSoon> holdsEndingSoon,
    List<UnitView> unitsExpiringSoon,
    List<ActivityEntry> recentActivity) {
}
