package com.abetappteam.abetapp.service;

import com.abetappteam.abetapp.dto.report.IndicatorReportData;
import com.abetappteam.abetapp.dto.report.MultiYearReportData;
import com.abetappteam.abetapp.dto.report.OutcomeReportData;
import com.abetappteam.abetapp.dto.report.ReportMeasureData;
import com.abetappteam.abetapp.entity.Course;
import com.abetappteam.abetapp.entity.CourseIndicator;
import com.abetappteam.abetapp.entity.Measure;
import com.abetappteam.abetapp.entity.MeasureResult;
import com.abetappteam.abetapp.entity.Outcome;
import com.abetappteam.abetapp.entity.PerformanceIndicator;
import com.abetappteam.abetapp.entity.ScheduleEntry;
import com.abetappteam.abetapp.entity.Section;
import com.abetappteam.abetapp.entity.SectionProgram;
import com.abetappteam.abetapp.entity.Semester;
import com.abetappteam.abetapp.exception.BusinessException;
import com.abetappteam.abetapp.repository.CourseIndicatorRepository;
import com.abetappteam.abetapp.repository.CourseRepository;
import com.abetappteam.abetapp.repository.MeasureRepository;
import com.abetappteam.abetapp.repository.MeasureResultRepository;
import com.abetappteam.abetapp.repository.OutcomeRepository;
import com.abetappteam.abetapp.repository.PerformanceIndicatorRepository;
import com.abetappteam.abetapp.repository.ScheduleEntryRepository;
import com.abetappteam.abetapp.repository.SectionProgramRepository;
import com.abetappteam.abetapp.repository.SectionRepository;
import com.abetappteam.abetapp.repository.SemesterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Service for generating multi-year summary reports.
 */
@Service
public class MultiYearReportService {

    private static final Logger logger = LoggerFactory.getLogger(MultiYearReportService.class);

    private final SemesterRepository semesterRepository;
    private final CourseRepository courseRepository;
    private final CourseIndicatorRepository courseIndicatorRepository;
    private final MeasureRepository measureRepository;
    private final MeasureResultRepository measureResultRepository;
    private final OutcomeRepository outcomeRepository;
    private final PerformanceIndicatorRepository performanceIndicatorRepository;
    private final SectionProgramRepository sectionProgramRepository;
    private final SectionRepository sectionRepository;
    private final ScheduleEntryRepository scheduleEntryRepository;

    @Autowired
    public MultiYearReportService(
            SemesterRepository semesterRepository,
            CourseRepository courseRepository,
            CourseIndicatorRepository courseIndicatorRepository,
            MeasureRepository measureRepository,
            MeasureResultRepository measureResultRepository,
            OutcomeRepository outcomeRepository,
            PerformanceIndicatorRepository performanceIndicatorRepository,
            SectionProgramRepository sectionProgramRepository,
            SectionRepository sectionRepository,
            ScheduleEntryRepository scheduleEntryRepository) {
        this.semesterRepository = semesterRepository;
        this.courseRepository = courseRepository;
        this.courseIndicatorRepository = courseIndicatorRepository;
        this.measureRepository = measureRepository;
        this.measureResultRepository = measureResultRepository;
        this.outcomeRepository = outcomeRepository;
        this.performanceIndicatorRepository = performanceIndicatorRepository;
        this.sectionProgramRepository = sectionProgramRepository;
        this.sectionRepository = sectionRepository;
        this.scheduleEntryRepository = scheduleEntryRepository;
    }

    // Returns the semesters for the given program whose dates fall within
    // startDate, endDate
    @Transactional(readOnly = true)
    public List<Semester> getSemestersInDateRange(Long programId, LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("End date cannot be before start date");
        }
        return semesterRepository.searchSemesters(null, null, null, startDate, endDate, null, null, null)
                .stream()
                .filter(s -> programId.equals(s.getProgramId()))
                .toList();
    }

    // Returns all active measures for the given program across the date range
    @Transactional(readOnly = true)
    public List<Measure> getMeasuresInDateRange(Long programId, LocalDate startDate, LocalDate endDate) {
        List<Semester> semesters = getSemestersInDateRange(programId, startDate, endDate);
        logger.info("Found {} semesters in date range [{}, {}] for program {}",
                semesters.size(), startDate, endDate, programId);

        List<Integer> semesterIds = semesters.stream()
                .map(s -> s.getId().intValue())
                .toList();

        List<Measure> output = measureRepository.findMeasuresByProgramAndSemesters(
                programId.intValue(), semesterIds, true);

        logger.info("Aggregated {} measures for program {} in date range [{}, {}]",
                output.size(), programId, startDate, endDate);
        return output;
    }

    /**
     * Builds a report for a single semester by ID.
     */
    @Transactional(readOnly = true)
    public MultiYearReportData buildReportForSemester(Long programId, Long semesterId) {
        Semester semester = semesterRepository.findById(semesterId)
                .orElseThrow(() -> new BusinessException("Semester not found: " + semesterId));

        Set<Integer> semesterIdSet = Set.of(semesterId.intValue());
        List<Semester> semesters = List.of(semester);

        String academicYear = semester.getAcademicYear() != null
                ? String.format("%d\u2013%d", semester.getAcademicYear(), semester.getAcademicYear() + 1)
                : String.valueOf(semester.getStartDate().getYear());
        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("M/d/yyyy"));

        return buildReportForSemesters(programId, semesterIdSet, semesters, academicYear, semester.getName(), generatedDate);
    }

    /**
     * Builds one report per academic year for the given date range.
     * Years with no measure data are included with an empty outcomes list.
     */
    @Transactional(readOnly = true)
    public List<MultiYearReportData> buildReportByAcademicYear(Long programId, LocalDate startDate, LocalDate endDate) {
        List<Semester> allSemesters = getSemestersInDateRange(programId, startDate, endDate);
        if (allSemesters.isEmpty()) {
            throw new BusinessException("No semesters found in the selected date range");
        }

        // Group semesters by academic year (sorted ascending)
        Map<Integer, List<Semester>> semestersByYear = allSemesters.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getAcademicYear() != null ? s.getAcademicYear() : s.getStartDate().getYear(),
                        TreeMap::new,
                        Collectors.toList()));

        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("M/d/yyyy"));
        List<MultiYearReportData> result = new ArrayList<>();

        for (Map.Entry<Integer, List<Semester>> entry : semestersByYear.entrySet()) {
            Integer year = entry.getKey();
            List<Semester> yearSemesters = entry.getValue();

            Set<Integer> semesterIdSet = yearSemesters.stream()
                    .map(s -> s.getId().intValue())
                    .collect(Collectors.toSet());

            String academicYear = String.format("%d\u2013%d", year, year + 1);
            LocalDate yearStart = yearSemesters.stream()
                    .map(Semester::getStartDate)
                    .min(Comparator.naturalOrder())
                    .orElse(startDate);
            LocalDate yearEnd = yearSemesters.stream()
                    .map(Semester::getEndDate)
                    .max(Comparator.naturalOrder())
                    .orElse(endDate);
            String semesterName = String.format("%s \u2013 %s", yearStart, yearEnd);

            MultiYearReportData yearReport;
            try {
                yearReport = buildReportForSemesters(
                        programId, semesterIdSet, yearSemesters, academicYear, semesterName, generatedDate);
            } catch (BusinessException e) {
                // No data for this year — include an empty placeholder
                logger.info("No data for academic year {}: {}", academicYear, e.getMessage());
                yearReport = new MultiYearReportData(
                        yearSemesters.get(0).getId(), semesterName, academicYear, generatedDate);
                yearReport.setOutcomes(new ArrayList<>());
            }
            result.add(yearReport);
        }

        return result;
    }

    /**
     * Builds a multi-year report aggregated across the entire date range (no year grouping).
     */
    @Transactional(readOnly = true)
    public MultiYearReportData buildHierarchicalReport(Long programId, LocalDate startDate, LocalDate endDate) {
        List<Semester> semesters = getSemestersInDateRange(programId, startDate, endDate);
        if (semesters.isEmpty()) {
            throw new BusinessException("No semesters found in the selected date range");
        }

        Set<Integer> semesterIdSet = semesters.stream()
                .map(s -> s.getId().intValue())
                .collect(Collectors.toSet());

        String semesterName = String.format("%s \u2013 %s", startDate, endDate);
        String academicYear = String.format("%s\u2013%s", startDate.getYear(), endDate.getYear());
        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("M/d/yyyy"));

        return buildReportForSemesters(programId, semesterIdSet, semesters, academicYear, semesterName, generatedDate);
    }

    /** UPDATE THIS
     * Core report building logic for a given set of semesters.
     *
     * Traversal order: program → sectionProgram → section
     * → course → courseIndicator → measures
     * ALSO: schedule_entry → measures (via schedule_entry_id)
     * → indicator → outcome → measure_results (via measure_id)
     */

    private MultiYearReportData buildReportForSemesters(
            Long programId,
            Set<Integer> semesterIdSet,
            List<Semester> semesters,
            String academicYear,
            String semesterName,
            String generatedDate) {

        logger.info("Building CLEAN schedule-driven report for {}", academicYear);

        // STEP 1: Pull ALL schedule entries for program + semesters
        List<ScheduleEntry> scheduleEntries = semesterIdSet.stream()
                .flatMap(semId ->
                        scheduleEntryRepository
                                .findBySemesterIdAndProgramId(programId.intValue(), semId)
                                .stream()
                )
                .toList();

        if (scheduleEntries.isEmpty()) {
            throw new BusinessException("No schedule entries found for selected range");
        }

        // DTO maps
        Map<Long, OutcomeReportData> outcomeMap = new LinkedHashMap<>();
        Map<Long, IndicatorReportData> indicatorMap = new LinkedHashMap<>();

        // Aggregation helpers
        Map<Long, List<Double>> indicatorToMeasureAverages = new HashMap<>();
        Map<Long, List<Double>> outcomeToIndicatorAverages = new HashMap<>();

        // STEP 2: Iterate schedule entries
        for (ScheduleEntry se : scheduleEntries) {

            PerformanceIndicator indicator = performanceIndicatorRepository
                    .findById((long) se.getIndicatorId())
                    .orElse(null);

            if (indicator == null || !Boolean.TRUE.equals(indicator.getIsActive())) continue;

            Outcome outcome = outcomeRepository
                    .findById(indicator.getStudentOutcomeId())
                    .orElse(null);

            if (outcome == null || !Boolean.TRUE.equals(outcome.getActive())) continue;

            // Create DTOs
            OutcomeReportData outcomeDto = outcomeMap.computeIfAbsent(
                    outcome.getId(),
                    id -> new OutcomeReportData(
                            outcome.getId(),
                            outcome.getNumber(),
                            outcome.getDescription(),
                            ""
                    )
            );

            IndicatorReportData indicatorDto = indicatorMap.computeIfAbsent(
                    indicator.getId(),
                    id -> new IndicatorReportData(
                            indicator.getId(),
                            outcome.getNumber() + "." + indicator.getIndicatorNumber(),
                            null,
                            0
                    )
            );

            // STEP 3: Measures from schedule entry ONLY
            List<Measure> measures = measureRepository.findByScheduleEntryId(se.getId());
            if (measures.isEmpty()) continue;

            for (Measure measure : measures) {

                List<MeasureResult> results =
                        measureResultRepository.findByMeasureId(measure.getId());

                int met = 0;
                int exceeded = 0;
                int below = 0;

                for (MeasureResult r : results) {
                    met += r.getStudentsMet() != null ? r.getStudentsMet() : 0;
                    exceeded += r.getStudentsExceeded() != null ? r.getStudentsExceeded() : 0;
                    below += r.getStudentsBelow() != null ? r.getStudentsBelow() : 0;
                }

                double measureAvg = calculateMetPercentage(met, exceeded, below);
                String status = determineStatus(measureAvg);

                ReportMeasureData measureDto = new ReportMeasureData(
                        measure.getId(),
                        null,
                        null,
                        measure.getDescription(),
                        met,
                        exceeded,
                        below,
                        measureAvg,
                        status,
                        null,
                        measure.getRecommendedAction()
                );

                indicatorDto.addMeasure(measureDto);

                // Track for indicator averaging
                indicatorToMeasureAverages
                        .computeIfAbsent(indicator.getId(), k -> new ArrayList<>())
                        .add(measureAvg);
            }
        }

        if (indicatorToMeasureAverages.isEmpty()) {
            throw new BusinessException("No measure data found for selected range");
        }

        // STEP 4: Indicator averages
        Map<Long, Double> indicatorAverages = new HashMap<>();

        for (Map.Entry<Long, List<Double>> entry : indicatorToMeasureAverages.entrySet()) {

            double avg = entry.getValue().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);

            indicatorAverages.put(entry.getKey(), avg);

            PerformanceIndicator indicator = performanceIndicatorRepository
                    .findById(entry.getKey())
                    .orElse(null);

            if (indicator != null) {
                outcomeToIndicatorAverages
                        .computeIfAbsent(indicator.getStudentOutcomeId(), k -> new ArrayList<>())
                        .add(avg);
            }
        }

        // STEP 5: Attach indicators to outcomes + compute outcome avg
        for (OutcomeReportData outcomeDto : outcomeMap.values()) {

            List<IndicatorReportData> indicators = indicatorMap.values().stream()
                    .filter(ind -> {
                        PerformanceIndicator pi = performanceIndicatorRepository
                                .findById(ind.getIndicatorId())
                                .orElse(null);
                        return pi != null && pi.getStudentOutcomeId().equals(outcomeDto.getOutcomeId());
                    })
                    .toList();

            outcomeDto.setIndicators(indicators);

            List<Double> indicatorAvgs =
                    outcomeToIndicatorAverages.getOrDefault(outcomeDto.getOutcomeId(), new ArrayList<>());

            double outcomeAvg = indicatorAvgs.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);

            outcomeDto.setOverallStatus(determineStatus(outcomeAvg));
        }

        if (outcomeMap.isEmpty()) {
            throw new BusinessException("No outcomes found for selected range");
        }

        // FINAL RESPONSE
        MultiYearReportData response = new MultiYearReportData(
                semesters.get(0).getId(),
                semesterName,
                academicYear,
                generatedDate
        );

        response.setOutcomes(new ArrayList<>(outcomeMap.values()));

        logger.info("Built CLEAN report with {} outcomes", outcomeMap.size());

        return response;
    }


    /**
     * Calculates the percentage of students that met or exceeded the measure.
     */
    private Double calculateMetPercentage(Integer met, Integer exceeded, Integer below) {
        Integer total = met + exceeded + below;
        if (total == 0) {
            return 0.0;
        }
        return Math.round(((met + exceeded) / (double) total) * 1000) / 10.0;
    }

    /**
     * Determines the status string based on met percentage.
     */
    private String determineStatus(Double percentage) {
        if (percentage >= 80) {
            return "Met comfortably";
        } else if (percentage >= 70) {
            return "Met";
        } else if (percentage >= 65) {
            return "Barely not met";
        }
        return "Not met";
    }

    /**
     * Determines the overall outcome status based on its indicators' measures.
     */
    private String determineOutcomeStatus(List<IndicatorReportData> indicators) {
        if (indicators.isEmpty()) {
            return "No Data";
        }

        int totalMeasures = 0;
        int metMeasures = 0;

        for (IndicatorReportData indicator : indicators) {
            for (ReportMeasureData measure : indicator.getMeasures()) {
                totalMeasures++;
                if ("Met comfortably".equals(measure.getStatus()) || "Met".equals(measure.getStatus())) {
                    metMeasures++;
                }
            }
        }

        if (totalMeasures == 0) {
            return "No Data";
        }

        double percentage = (metMeasures / (double) totalMeasures) * 100;
        if (percentage >= 80) {
            return "MET";
        } else if (percentage >= 50) {
            return "Partially Met";
        }
        return "Not Met";
    }
}
