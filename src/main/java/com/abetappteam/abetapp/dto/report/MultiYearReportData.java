package com.abetappteam.abetapp.dto.report;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Root response for multi-year report with hierarchically organized outcomes,
 * indicators, and measures
 */
public class MultiYearReportData implements Serializable {
    private Long semesterId;
    private String semesterName;
    private String academicYear;
    private String generatedDate;
    private List<String> generatedBy;
    private List<OutcomeReportData> outcomes;

    public MultiYearReportData() {
        this.generatedBy = new ArrayList<>();
        this.outcomes = new ArrayList<>();
    }

    public MultiYearReportData(Long semesterId, String semesterName, String academicYear, String generatedDate) {
        this.semesterId = semesterId;
        this.semesterName = semesterName;
        this.academicYear = academicYear;
        this.generatedDate = generatedDate;
        this.generatedBy = new ArrayList<>();
        this.outcomes = new ArrayList<>();
    }

    // Getters and Setters
    public Long getSemesterId() {
        return semesterId;
    }
    public void setSemesterId(Long semesterId) {
        this.semesterId = semesterId;
    }

    public String getSemesterName() {
        return semesterName;
    }
    public void setSemesterName(String semesterName) {
        this.semesterName = semesterName;
    }

    public String getAcademicYear() {
        return academicYear;
    }
    public void setAcademicYear(String academicYear) {
        this.academicYear = academicYear;
    }

    public String getGeneratedDate() {
        return generatedDate;
    }
    public void setGeneratedDate(String generatedDate) {
        this.generatedDate = generatedDate;
    }

    public List<String> getGeneratedBy() {
        return generatedBy;
    }
    public void setGeneratedBy(List<String> generatedBy) {
        this.generatedBy = generatedBy;
    }

    public void addGeneratedBy(String name) {
        this.generatedBy.add(name);
    }

    public List<OutcomeReportData> getOutcomes() {
        return outcomes;
    }
    public void setOutcomes(List<OutcomeReportData> outcomes) {
        this.outcomes = outcomes;
    }

    public void addOutcome(OutcomeReportData outcome) {
        this.outcomes.add(outcome);
    }
}
