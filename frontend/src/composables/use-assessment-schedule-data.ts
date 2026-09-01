import { ref, computed } from 'vue';
import api from '@/api';

export interface Course {
  id: number;
  courseCode: string;
  courseName: string;
}

export interface StudentOutcome {
  id: number;
  number?: number;
  outNumber?: number;
  out_number?: number;
  description?: string;
  outDescription?: string;
  out_description?: string;
}

export interface PerformanceIndicator {
  id: number;
  indicatorNumber: number;
  indNumber?: number;
  ind_number?: number;
  indicatorDescription: string;
  indDescription?: string;
  ind_description?: string;
  studentOutcomeId: number;
  student_outcome_id?: number;
  outcomeNumber: number;
}

export interface IndicatorScheduleRow {
  indicatorId: number;
  outcomeNumber: number;
  indicatorNumber: number;
  indicatorDescription: string;
  assignments: Map<string, number[]>; // year -> course IDs
}

export interface CourseIndicatorMapping {
  courseId: number;
  courseCode: string;
  courseName: string;
  indicatorMappings: {
    [indicatorKey: string]: boolean; // "outcomeNum.indicatorNum" -> true
  };
}

interface AssessmentScheduleProps {
  programId: number | null;
  semesterId?: number | null;
  startYear?: number;
  yearCount?: number;
}

export function useAssessmentScheduleData(props: AssessmentScheduleProps) {
  const loading = ref(false);
  const error = ref<string | null>(null);
  const courses = ref<Course[]>([]);
  const outcomes = ref<StudentOutcome[]>([]);
  const indicators = ref<PerformanceIndicator[]>([]);
  const scheduleData = ref<IndicatorScheduleRow[]>([]);
  const courseIndicatorMappings = ref<CourseIndicatorMapping[]>([]);

  // Generate academic years
  const academicYears = computed(() => {
    const start = props.startYear || new Date().getFullYear();
    const count = props.yearCount || 6;
    const years: string[] = [];

    for (let i = 0; i < count; i++) {
      const yearStart = start + i;
      const yearEnd = yearStart + 1;
      years.push(`${yearStart}-${yearEnd}`);
    }

    return years;
  });

  // Get course codes for a cell
  function getCourseCodesForCell(indicatorId: number, year: string): string {
    const row = scheduleData.value.find(r => r.indicatorId === indicatorId);
    if (!row) return '';

    const courseIds = row.assignments.get(year) || [];
    if (courseIds.length === 0) return '';

    const courseCodes = courseIds
      .map(id => {
        const course = courses.value.find(c => c.id === id);
        if (!course) return null;
        // Remove "CS " prefix for brevity
        return course.courseCode.replace(/^CS\s*/i, '').trim();
      })
      .filter(code => code !== null)
      .sort((a, b) => {
        const numA = parseInt(a!) || 0;
        const numB = parseInt(b!) || 0;
        return numA - numB;
      });

    return courseCodes.length > 0 ? `(${courseCodes.join(', ')})` : '';
  }

  // Get full course names for tooltip
  function getCourseNamesForCell(indicatorId: number, year: string): string {
    const row = scheduleData.value.find(r => r.indicatorId === indicatorId);
    if (!row) return '';

    const courseIds = row.assignments.get(year) || [];
    if (courseIds.length === 0) return 'No courses assigned';

    const courseNames = courseIds
      .map(id => {
        const course = courses.value.find(c => c.id === id);
        return course ? `${course.courseCode} - ${course.courseName}` : null;
      })
      .filter(name => name !== null);

    return courseNames.join('\n');
  }

  // Get indicator description for tooltip
  function getIndicatorTooltip(indicatorId: number): string {
    // Try to find in scheduleData first (has formatted display data)
    const row = scheduleData.value.find(r => r.indicatorId === indicatorId);
    if (row && row.indicatorDescription) {
      return `${row.outcomeNumber}.${row.indicatorNumber}: ${row.indicatorDescription}`;
    }

    // Fallback to indicators array
    const indicator = indicators.value.find(ind => ind.id === indicatorId);
    if (indicator && indicator.indicatorDescription) {
      return `${indicator.outcomeNumber}.${indicator.indicatorNumber}: ${indicator.indicatorDescription}`;
    }

    // Last resort - just show the indicator number
    if (row) {
      return `Indicator ${row.outcomeNumber}.${row.indicatorNumber}`;
    }
    if (indicator) {
      return `Indicator ${indicator.outcomeNumber}.${indicator.indicatorNumber}`;
    }

    return 'No description available';
  }

  // Load data from backend
  async function loadData() {
    // Guard against null programId
    if (!props.programId) {
      error.value = 'Program ID is required';
      return;
    }

    loading.value = true;
    error.value = null;

    try {
      // Load courses
      const coursesRes = await api.get(`/program/${props.programId}/courses/active`);
      courses.value = coursesRes.data.data || [];

      // Load outcomes - use semesterId if provided, otherwise get latest
      if (props.semesterId) {
        // Use the provided semester ID
        const outcomesRes = await api.get(`/outcome/bySemester/${props.semesterId}`);
        outcomes.value = outcomesRes.data.data || [];
      } else {
        // Fall back to latest semester
        const semestersRes = await api.get('/semesters', {
          params: { programId: props.programId }
        });

        const semesters = semestersRes.data.content || [];

        if (semesters.length > 0) {
          const latestSemester = semesters[0];
          const outcomesRes = await api.get(`/outcome/bySemester/${latestSemester.id}`);
          outcomes.value = outcomesRes.data.data || [];
        } else {
          error.value = 'No semesters found for this program';
          outcomes.value = [];
        }
      }

      // Load all performance indicators for these outcomes
      await loadIndicators();

      // Initialize schedule data
      await buildScheduleData();

    } catch (err) {
      console.error('Error loading data:', err);
      error.value = 'Failed to load assessment schedule';
    } finally {
      loading.value = false;
    }
  }

  // Load all performance indicators
  async function loadIndicators() {
    const allIndicators: PerformanceIndicator[] = [];

    for (const outcome of outcomes.value) {
      try {
        const outcomeNumber = outcome.number ?? outcome.outNumber ?? outcome.out_number ?? 0;
        const indicatorsRes = await api.get('/performance-indicators/by-outcome/active', {
          params: { studentOutcomeId: outcome.id }
        });
        const outcomeIndicators = indicatorsRes.data.data || [];

        // Add outcome number to each indicator
        outcomeIndicators.forEach((ind: any) => {
          // Database uses ind_number and ind_description - check these first
          const indicatorNum = ind.indNumber ?? ind.ind_number ??
            ind.indicatorNumber ?? ind.indicator_number ?? 0;
          const indicatorDesc = ind.indDescription ?? ind.ind_description ??
            ind.indicatorDescription ?? ind.indicator_description ??
            ind.description ?? '';

          allIndicators.push({
            id: ind.id,
            indicatorNumber: indicatorNum,
            indicatorDescription: indicatorDesc,
            studentOutcomeId: ind.studentOutcomeId ?? ind.student_outcome_id ?? outcome.id,
            outcomeNumber: outcomeNumber
          });
        });
      } catch (err) {
        console.error(`Error loading indicators for outcome ${outcome.id}:`, err);
      }
    }

    // Sort by outcome number, then indicator number
    allIndicators.sort((a, b) => {
      if (a.outcomeNumber !== b.outcomeNumber) {
        return a.outcomeNumber - b.outcomeNumber;
      }
      return a.indicatorNumber - b.indicatorNumber;
    });

    indicators.value = allIndicators;
  }

  // Build schedule data structure
  async function buildScheduleData() {
    const data: IndicatorScheduleRow[] = [];
    const courseMappingMap = new Map<number, CourseIndicatorMapping>();

    // Initialize course mappings
    courses.value.forEach(course => {
      courseMappingMap.set(course.id, {
        courseId: course.id,
        courseCode: course.courseCode,
        courseName: course.courseName,
        indicatorMappings: {}
      });
    });

    // Create a row for each indicator
    for (const indicator of indicators.value) {
      const assignments = new Map<string, number[]>();

      // Initialize all years with empty arrays
      academicYears.value.forEach(year => {
        assignments.set(year, []);
      });

      const row = {
        indicatorId: indicator.id,
        outcomeNumber: indicator.outcomeNumber,
        indicatorNumber: indicator.indicatorNumber,
        indicatorDescription: indicator.indicatorDescription,
        assignments
      };

      data.push(row);
    }

    // Build course-indicator mappings
    for (const course of courses.value) {
      try {
        const indicatorsRes = await api.get(`/courses/${course.id}/indicators`);
        const indicatorIds = indicatorsRes.data || [];

        // Mark which indicators this course assesses
        for (const indicatorId of indicatorIds) {
          const indicator = indicators.value.find(ind => ind.id === indicatorId);
          if (indicator) {
            const courseMapping = courseMappingMap.get(course.id);
            if (courseMapping) {
              const key = `${indicator.outcomeNumber}.${indicator.indicatorNumber}`;
              courseMapping.indicatorMappings[key] = true;
            }
          }
        }
      } catch (err) {
        console.error(`Error loading indicators for course ${course.id}:`, err);
      }
    }

    scheduleData.value = data;
    courseIndicatorMappings.value = Array.from(courseMappingMap.values());
  }

  return {
    loading,
    error,
    courses,
    outcomes,
    indicators,
    scheduleData,
    courseIndicatorMappings,
    academicYears,
    loadData,
    getCourseCodesForCell,
    getCourseNamesForCell,
    getIndicatorTooltip
  };
}
