package com.farm.irrigation.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result summary of one evaluation cycle, returned by POST /api/control/run-cycle. */
public class CycleReport {

    private final List<Long> startedJobs = new ArrayList<>();
    private final List<Map<String, Object>> decisions = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public void addStarted(Long jobId) {
        startedJobs.add(jobId);
    }

    public void addDecision(Long fieldId, DecisionResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fieldId", fieldId);
        m.put("decision", r.getDecision());
        m.put("stage", r.getStage());
        m.put("thetaStart", r.getThetaStart());
        m.put("thetaTarget", r.getThetaTarget());
        m.put("volumeM3", r.getVolumeM3());
        m.put("durationSec", r.getDurationSec());
        m.put("fallback", r.isFallback());
        m.put("reasons", r.getReasons());
        decisions.add(m);
    }

    public void addNote(String note) {
        notes.add(note);
    }

    public List<Long> getStartedJobs() { return startedJobs; }
    public List<Map<String, Object>> getDecisions() { return decisions; }
    public List<String> getNotes() { return notes; }

    @Override
    public String toString() {
        return "CycleReport{started=" + startedJobs + ", decisions=" + decisions.size()
                + ", notes=" + notes.size() + "}";
    }
}
