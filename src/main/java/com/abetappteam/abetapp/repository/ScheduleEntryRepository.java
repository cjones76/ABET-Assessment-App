package com.abetappteam.abetapp.repository;

import com.abetappteam.abetapp.entity.ScheduleEntry;
import com.abetappteam.abetapp.entity.Requests.ScheduleEntry.ScheduleEntrySearchRequest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ScheduleEntryRepository extends JpaRepository<ScheduleEntry, Long> {

    @Modifying
    @Transactional
    void deleteByCourseId(int courseId);
    // Semester ID queries
    List<ScheduleEntry> findBySemesterId(int semesterId);
    long countBySemesterId(int semesterId);

    // Course ID queries
    List<ScheduleEntry> findByCourseId(int courseId);
    long countByCourseId(int courseId);

    List<ScheduleEntry> findBySemesterIdAndProgramId(int semesterId, int programId);
    long countBySemesterIdAndProgramId(int semesterId, int programId);

    // Program ID queries
    List<ScheduleEntry> findByProgramId(int programId);
    long countByProgramId(int programId);

    // Indicator ID queries
    List<ScheduleEntry> findByIndicatorId(int indicatorId);
    long countByIndicatorId(int indicatorId);

    // Search
    @Query("SELECT se FROM ScheduleEntry se" +
            " WHERE (:#{#request.id()} IS NULL OR se.id = :#{#request.id()}) " +
            "AND (:#{#request.semesterId()} IS NULL OR se.semesterId = :#{#request.semesterId()}) " +
            "AND (:#{#request.courseId()} IS NULL OR se.courseId = :#{#request.courseId()}) " +
            "AND (:#{#request.programId()} IS NULL OR se.programId = :#{#request.programId()}) " +
            "AND (:#{#request.indicatorId()} IS NULL OR se.indicatorId = :#{#request.indicatorId()})")
    List<ScheduleEntry> searchScheduleEntry(@Param("request") ScheduleEntrySearchRequest request);

    // Find schedule entries for a program whose semester falls within the given date range
    @Query("SELECT se FROM ScheduleEntry se, Semester s " +
            "WHERE se.semesterId = s.id " +
            "AND se.programId = :programId " +
            "AND s.startDate >= :startDate " +
            "AND s.endDate <= :endDate")
    List<ScheduleEntry> findByProgramIdAndSemesterDateRange(
            @Param("programId") int programId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Exists by semester and program
    @Query("SELECT COUNT(se) > 0 FROM ScheduleEntry se " +
            "WHERE se.semesterId = :semesterId AND se.programId = :programId")
    Boolean existsBySemesterIdAndProgramId(@Param("semesterId") int semesterId,
                                       @Param("programId") int programId);

    // Exists by all fields
    @Query("SELECT COUNT(se) > 0 FROM ScheduleEntry se " +
            "WHERE (se.semesterId = :semesterId) " +
            "AND (se.courseId = :courseId) " +
            "AND (se.indicatorId = :indicatorId) " +
            "AND (se.programId = :programId)")
    Boolean existsBySemesterIdAndCourseIdAndProgramIdAndIndicatorId(@Param("semesterId") int semesterId,
                                           @Param("courseId") int courseId,
                                           @Param("indicatorId") int indicatorId,
                                           @Param("programId") int programId);
}
