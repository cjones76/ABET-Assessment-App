<script setup lang="ts">
import { ref, watch } from "vue";
import api from "@/api";
import BaseModal from "@/components/ui/BaseModal.vue";
import BaseCard from "@/components/ui/BaseCard.vue";
import BaseSpinner from "@/components/ui/BaseSpinner.vue";
import {BaseButton} from "@/components/ui";
import {storeToRefs} from "pinia";
import {useUserStore} from "@/stores/user-store.ts";

interface Course {
  id: number;
  courseCode: string;
  courseName: string;
  courseDescription?: string;
  studentCount?: number;
  threshold?: number;
}

interface Section {
  id: number;
  formattedName: string;
  instructor: string;
}

interface PerformanceIndicator {
  id: number;
  indicatorNumber: number;
  description: string;
  thresholdPercentage: number;
  studentOutcomeId: number;
}

interface Measure {
  id: number;
  description: string;
  observation?: string;
  recommendedAction?: string;
  studentsMet?: number;
  studentsExceeded?: number;
  studentsBelow?: number;
  status: string;
}

interface IndicatorWithMeasures {
  indicator: PerformanceIndicator;
  measures: Measure[];
}

const props = defineProps<{
  course: Course;
}>();

const emit = defineEmits(["close"]);
const userStore = useUserStore();

const showNewSectionForm = ref(false);
const newSectionNumber = ref("");
const newInstructorId = ref<number | null>(null);
const submittingSection = ref(false);
const loading = ref(false);
const error = ref<string | null>(null);
const indicatorsWithMeasures = ref<IndicatorWithMeasures[]>([]);
const { currentProgramId, currentSemesterId } = storeToRefs(userStore);
const currentCourseSemesterSections = ref<Section[]>([]);
const availableInstructors = ref<Array<{id: number; fullName: string}>>([]);

/* -----------------------------------------------
 * Load course indicators and measures
 * ----------------------------------------------- */
async function loadCourseData() {
  if (!props.course) return;

  loading.value = true;
  error.value = null;
  indicatorsWithMeasures.value = [];
  currentCourseSemesterSections.value = [];

  try {

    const sectionRes = await api.get(`/section`, {
      params: {
        courseId: props.course.id,
        semesterId: currentSemesterId.value
      }
    });

    const sectionsData = sectionRes.data?.data ?? [];

    for (const section of sectionsData.sections) {

      const sectionUserRes = await api.get(`/section-user`, {
        params: {
          sectionId: section.id
        }
      });

      const userId = sectionUserRes.data?.data?.[0]?.userId;

      var userName = "Unassigned";

      if(userId > 0) {
        const userRes = await api.get(`/users/${userId}`);
        userName = userRes.data?.data?.fullName;
      }

      currentCourseSemesterSections.value.push({
        id: section.id,
        formattedName: `${props.course.courseCode} ${props.course.courseName} ${section.sectionNumber}`,
        instructor: userName
      });
    }


    const indicatorIdsRes = await api.get(`/courses/searchCourse`, {
      params: {
        courseId: props.course.id
      }
    });

    const res = await api.get(`/courses/searchCourse`, { // Added "const res ="
      params: { courseId: props.course.id }
    });
    const rawData = res.data?.data ?? res.data ?? [];

    // 3. Ensure we are working with an array of numbers
    // This helps TypeScript understand exactly what 'indicatorId' is
    const indicatorIds: number[] = Array.isArray(rawData)
      ? rawData.map((item: any) => typeof item === 'object' ? item.id : item)
      : [];

    if (indicatorIds.length === 0) {
      loading.value = false;
      return;
    }

    // Now indicatorId won't be red because TypeScript knows indicatorIds is number[]
    const indicatorPromises = indicatorIds.map(async (indicatorId) => {
      try {
        const indicatorRes = await api.get(`/performance-indicators/${indicatorId}`);
        const indicator = indicatorRes.data.data as PerformanceIndicator;

        const measuresRes = await api.get(`/measure/byIndicator/${indicatorId}`);
        const measures = measuresRes.data.data as Measure[];

        return { indicator, measures };
      } catch (err) {
        console.error(`Error loading indicator ${indicatorId}:`, err);
        return null;
      }
    });

    const results = await Promise.all(indicatorPromises);
    indicatorsWithMeasures.value = results.filter(
      (item): item is IndicatorWithMeasures => item !== null
    );

  } catch (err: any) {
    console.error("Failed to load course data:", err);
    error.value = "Failed to load course details. Please try again.";
  } finally {
    loading.value = false;
  }
}

/* -----------------------------------------------
 * Watch for course changes
 * ----------------------------------------------- */
watch(
  () => props.course,
  (newCourse) => {
    if (newCourse) {
      loadCourseData();
    } else {
      console.log("No course provided, clearing data");
      indicatorsWithMeasures.value = [];
      error.value = null;
    }
  },
  { immediate: true }
);

/* -----------------------------------------------
 * Delete Logic
 * ----------------------------------------------- */
const deleting = ref(false);

async function deleteCourse() {
  if (!props.course) return;

  const confirmed = window.confirm(
    `Are you sure you want to delete ${props.course.courseCode}? This action will also delete all associated indicators and measures. This cannot be undone.`
  );
  if (!confirmed) return;

  deleting.value = true;
  error.value = null;

  try {
    await api.delete(`/courses/${props.course.id}`);

    emit("close");
    window.location.reload();
  } catch (err: any) {
    console.error("Failed to delete course:", err);

    const backendMessage = err?.response?.data?.message;

    if (err?.response?.status === 400 || err?.response?.status === 409) {
      error.value = backendMessage || "This course cannot be deleted because it has measures currently in review.";
    } else {
      error.value = "An unexpected error occurred. Please try again later.";
    }

    const modalBody = document.querySelector('.course-details');
    if (modalBody) modalBody.scrollTop = 0;

  } finally {
    deleting.value = false;
  }
}

async function deleteSection(sectionId: number) {
  const confirmed = window.confirm(
    `Are you sure you want to delete this section? This action cannot be undone.`
  );
  if (!confirmed) return;

  try {
    await api.delete(`/section/${sectionId}`);
    loadCourseData();
  } catch (err: any) {
    console.error("Failed to delete section:", err);
    alert(err?.response?.data?.message || "Failed to delete section. Please try again.");
  }
}

/* -----------------------------------------------
 * Helper functions
 * ----------------------------------------------- */
function getStatusClass(status: string): string {
  switch (status) {
    case 'InProgress':
      return 'status-in-progress';
    case 'InReview':
      return 'status-in-review';
    case 'Submitted':
      return 'status-submitted';
    case 'Complete':
      return 'status-complete';
    default:
      return '';
  }
}

function getStatusLabel(status: string): string {
  switch (status) {
    case 'InProgress':
      return 'In Progress';
    case 'InReview':
      return 'In Review';
    case 'Submitted':
      return 'Submitted';
    case 'Complete':
      return 'Complete';
    default:
      return status;
  }
}

function calculatePercentage(met: number, exceeded: number, total: number): number {
  if (total === 0) return 0;
  return Math.round(((met + exceeded) / total) * 100);
}

function openNewSectionForm() {
  showNewSectionForm.value = true;
  newSectionNumber.value = "";
  newInstructorId.value = null;
  loadInstructors();
}

async function loadInstructors() {
  try {
    const res = await api.get(`/users`, {
      params: {
        role: "INSTRUCTOR",
        semesterId: currentSemesterId.value
      }
    });
    console.log(res)
    availableInstructors.value = res.data?.content ?? [];
  } catch (err: any) {
    console.error("Failed to load instructors:", err);
    availableInstructors.value = [];
  }
}

function cancelNewSection() {
  showNewSectionForm.value = false;
  newSectionNumber.value = "";
  newInstructorId.value = null;
}

async function submitNewSection() {
  if (!newSectionNumber.value.trim()) {
    error.value = "Please enter a section number.";
    return;
  }

  submittingSection.value = true;
  error.value = null;

  try {
    const sectionRes = await api.post(`/section`, {
        courseId: props.course.id,
        semesterId: currentSemesterId.value,
        sectionNumber: newSectionNumber.value.trim().toString()
    });

    const newSection = sectionRes.data?.data;
    if (newSection) {
      // Assign instructor if one was selected
      let instructorName = "Unassigned";

      if (newInstructorId.value) {
        try {
          await api.post(`/section-user`, {
            sectionId: newSection.id,
            userId: newInstructorId.value
          });

          // Get instructor name
          const selectedInstructor = availableInstructors.value.find(
            i => i.id === newInstructorId.value
          );
          instructorName = selectedInstructor?.fullName || "Assigned";
        } catch (err: any) {
          console.error("Failed to assign instructor:", err);
          // Continue even if instructor assignment fails
        }
      }

      currentCourseSemesterSections.value.push({
        id: newSection.id,
        formattedName: `${props.course.courseCode} ${props.course.courseName} ${newSection.sectionNumber}`,
        instructor: instructorName
      });
    }

    showNewSectionForm.value = false;
    newSectionNumber.value = "";
    newInstructorId.value = null;
  } catch (err: any) {
    console.error("Failed to create section:", err);
    error.value = err?.response?.data?.message || "Failed to create section. Please try again.";
  } finally {
    submittingSection.value = false;
  }
}

function openSectionDetails(sectionId: number) {
  window.open(`/section/${sectionId}`, "_blank");
}
</script>

<template>
  <BaseModal
    :is-open="!!course"
    @close="emit('close')"
    size="xl"
  >

    <div class="course-code-badge">
      <h2>{{ course?.courseName }}</h2>
      {{ course?.courseCode }}
    </div>

    <div v-if="error" class="error-banner">
      <p>{{ error }}</p>
      <button @click="error = null" class="error-close">×</button>
    </div>

    <div v-if="loading" class="loading-container">
      <BaseSpinner size="lg" text="Loading course details..." />
    </div>

    <div v-else class="course-details">
      <section class="info-section">
        <p v-if="course?.courseDescription" class="description">
          {{ course.courseDescription }}
        </p>
      </section>

      <section class="detail-section">
        <h3>Sections ({{ currentCourseSemesterSections.length }})</h3>

        <div>
          <table class="courses-table">
            <thead>
            <tr>
              <th>Section Name</th>
              <th>Primary Instructor</th>
              <th style="width: 50px; text-align: center;">Actions</th>
            </tr>
            </thead>
            <tbody>
            <tr
              v-for="section in currentCourseSemesterSections"
              :key="section.id"
              class="course-row clickable"
              @click="openSectionDetails(section.id)"
            >
              <td>{{ section.formattedName }}</td>
              <td>{{ section.instructor }} </td>
              <td style="text-align: center;" @click.stop>
                <button
                  @click="deleteSection(section.id)"
                  class="btn-delete-icon"
                  title="Delete section"
                  style="background: rgb(220, 53, 69)"
                >
                  <img src="@/assets/trashcan.png" alt="Delete" class="icon-trash" />
                </button>
              </td>
            </tr>
            <tr v-if="showNewSectionForm" class="course-row form-row">
              <td colspan="2">
                <div class="new-section-form">
                  <input
                    v-model="newSectionNumber"
                    type="text"
                    placeholder="Enter section number"
                    class="section-input"
                    @keyup.enter="submitNewSection"
                  />
                  <select
                    v-model.number="newInstructorId"
                    class="instructor-select"
                  >
                    <option :value="null">Select Instructor (Optional)</option>
                    <option
                      v-for="instructor in availableInstructors"
                      :key="instructor.id"
                      :value="instructor.id"
                    >
                      {{ instructor.fullName }}
                    </option>
                  </select>
                  <button
                    @click="submitNewSection"
                    :disabled="submittingSection"
                    class="btn-submit"
                  >
                    <span v-if="submittingSection">Creating...</span>
                    <span v-else>Create Section</span>
                  </button>
                  <button
                    @click="cancelNewSection"
                    :disabled="submittingSection"
                    class="btn-cancel"
                  >
                    Cancel
                  </button>
                </div>
              </td>
            </tr>
            <tr
              v-if="!showNewSectionForm"
              class="course-row clickable"
              @click="openNewSectionForm()">
                  <td :style="{ color: 'green' }">Add a new section +</td>
                  <td></td>
            </tr>
            </tbody>
          </table>
        </div>

      </section>

      <!-- Empty State -->
      <div v-if="indicatorsWithMeasures.length === 0" class="empty-state">
        <p>No performance indicators attached to this course.</p>
      </div>

      <div v-else class="indicators-container">
        <BaseCard
          v-for="item in indicatorsWithMeasures"
          :key="item.indicator.id"
          variant="bordered"
          class="indicator-card"
        >
          <div class="indicator-header">
            <div class="indicator-title">
              <span class="indicator-number">PI {{ item.indicator.indicatorNumber }}</span>
              <h3>{{ item.indicator.description }}</h3>
            </div>
          </div>

          <div v-if="item.measures.length > 0" class="measures-section">
            <h4 class="measures-title">Measures ({{ item.measures.length }})</h4>

            <div
              v-for="measure in item.measures"
              :key="measure.id"
              class="measure-item"
            >
              <div class="measure-header">
                <p class="measure-description">{{ measure.description }}</p>
                <span :class="['status-badge', getStatusClass(measure.status)]">
                  {{ getStatusLabel(measure.status) }}
                </span>
              </div>

              <div
                v-if="measure.studentsMet !== null || measure.studentsExceeded !== null"
                class="measure-data"
              >
                <div class="data-row">
                  <span class="data-label">Exceeded:</span>
                  <span class="data-value">{{ measure.studentsExceeded || 0 }}</span>
                </div>
                <div class="data-row">
                  <span class="data-label">Met:</span>
                  <span class="data-value">{{ measure.studentsMet || 0 }}</span>
                </div>
                <div class="data-row">
                  <span class="data-label">Below:</span>
                  <span class="data-value">{{ measure.studentsBelow || 0 }}</span>
                </div>
                <div class="data-row total">
                  <span class="data-label">Success Rate:</span>
                  <span class="data-value">
                    {{ calculatePercentage(
                    measure.studentsMet || 0,
                    measure.studentsExceeded || 0,
                    (measure.studentsMet || 0) +
                    (measure.studentsExceeded || 0) +
                    (measure.studentsBelow || 0)
                  ) }}%
                  </span>
                </div>
              </div>

              <div v-if="measure.observation" class="measure-observation">
                <strong>Observation:</strong>
                <p>{{ measure.observation }}</p>
              </div>

              <div v-if="measure.recommendedAction" class="measure-action">
                <strong>Recommended Action:</strong>
                <p>{{ measure.recommendedAction }}</p>
              </div>
            </div>
          </div>

          <div v-else class="no-measures">
            <p>No measures defined for this indicator.</p>
          </div>
        </BaseCard>
      </div>
    </div>

    <template #footer>
      <div class="footer-container">
        <BaseButton
          variant="danger"
          @click="deleteCourse"
          :disabled="loading || deleting"
          class="btn-action btn-delete"
        >
          <span v-if="deleting">Deleting...</span>
          <span v-else>Delete Course</span>
        </BaseButton>

        <button
          class="btn-action btn-close"
          @click="emit('close')"
          :disabled="deleting"
        >
          Close
        </button>
      </div>
    </template>

  </BaseModal>
</template>

<style scoped>
/* Courses Table */
.courses-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 0.5rem;
}

.courses-table th,
.courses-table td {
  padding: 0.75rem;
  text-align: left;
  border-bottom: 1px solid var(--color-border-light);
}

.courses-table th {
  background: var(--color-bg-tertiary);
  font-weight: 600;
  color: var(--color-text-primary);
  font-size: 0.875rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.courses-table tbody tr:hover {
  background: var(--color-bg-secondary);
}
.detail-section h3 {
  margin: 0 0 1rem 0;
  font-size: 1.125rem;
  color: var(--color-text-primary);
  border-bottom: 2px solid var(--color-border-light);
  padding-bottom: 0.5rem;
}
/* Clickable course rows */
.course-row.clickable {
  cursor: pointer;
  transition: background-color 0.2s;
}

.course-row.clickable:hover {
  background-color: rgba(255, 255, 255, 0.05);
}
/* Modal Header */
.modal-header-content {
  text-align: left;
}

.modal-header-content h2 {
  margin: 0 0 0.25rem 0;
  font-size: 1.5rem;
  color: var(--color-text-primary);
}

.course-code {
  margin: 0;
  color: var(--color-primary);
  font-weight: 600;
  font-size: 1rem;
}

.loading-container,
.error-container {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 3rem;
}

.error-message {
  color: var(--color-error);
  font-size: 1rem;
}

.course-details {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.info-section {
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--color-border-light);
}

.description {
  color: var(--color-text-secondary);
  font-style: italic;
  margin: 0 0 0.75rem 0;
}

.student-count {
  color: var(--color-text-secondary);
  font-size: 0.9rem;
  margin: 0;
}

.empty-state {
  text-align: center;
  padding: 2rem;
  color: var(--color-text-secondary);
}

.indicators-container {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.indicator-card {
  padding: 0;
}

.indicator-header {
  padding: 1.25rem;
  background: var(--color-bg-secondary);
  border-bottom: 2px solid var(--color-border-dark);
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
}

.indicator-title {
  flex: 1;
}

.indicator-number {
  display: inline-block;
  background: var(--color-primary);
  color: white;
  padding: 0.25rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.indicator-title h3 {
  margin: 0.5rem 0 0 0;
  font-size: 1.125rem;
  color: var(--color-text-primary);
  font-weight: 600;
}

.indicator-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 0.5rem;
}

.threshold {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  font-weight: 500;
}

.measures-section {
  padding: 1.25rem;
}

.measures-title {
  margin: 0 0 1rem 0;
  font-size: 1rem;
  color: var(--color-text-primary);
  font-weight: 600;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid var(--color-border-light);
}

.measure-item {
  padding: 1rem;
  margin-bottom: 1rem;
  background: var(--color-bg-primary);
  border: 1px solid var(--color-border-light);
  border-radius: 0.5rem;
}

.measure-item:last-child {
  margin-bottom: 0;
}

.measure-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  margin-bottom: 0.75rem;
}

.measure-description {
  flex: 1;
  margin: 0;
  font-weight: 500;
  color: var(--color-text-primary);
}

.status-badge {
  padding: 0.25rem 0.75rem;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  white-space: nowrap;
}

.status-in-progress {
  background: #3b82f6;
  color: white;
}

.status-in-review {
  background: #f59e0b;
  color: white;
}

.status-submitted {
  background: #8b5cf6;
  color: white;
}

.status-complete {
  background: #10b981;
  color: white;
}

.measure-data {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 0.75rem;
  margin-bottom: 0.75rem;
  padding: 0.75rem;
  background: var(--color-bg-secondary);
  border-radius: 0.375rem;
}

.data-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.data-row.total {
  grid-column: 1 / -1;
  padding-top: 0.5rem;
  border-top: 1px solid var(--color-border-light);
  font-weight: 600;
}

.data-label {
  color: var(--color-text-secondary);
  font-size: 0.875rem;
}

.data-value {
  color: var(--color-text-primary);
  font-weight: 600;
  font-size: 0.875rem;
}

.measure-observation,
.measure-action {
  margin-top: 0.75rem;
  padding: 0.75rem;
  background: var(--color-bg-secondary);
  border-radius: 0.375rem;
  border-left: 3px solid var(--color-primary);
}

.measure-observation strong,
.measure-action strong {
  display: block;
  margin-bottom: 0.25rem;
  color: var(--color-text-primary);
  font-size: 0.875rem;
}

.measure-observation p,
.measure-action p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  line-height: 1.5;
}

.no-measures {
  padding: 1.25rem;
  text-align: center;
  color: var(--color-text-secondary);
  font-style: italic;
}

.footer-container {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  gap: 1rem;
}

.btn-action {
  padding: 0.625rem 1.5rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 140px;
}

.btn-delete {
  background-color: transparent;
  color: #dc3545;
  border: 1px solid #dc3545;
}

.btn-delete:hover:not(:disabled) {
  background-color: #dc3545;
  color: white;
}

.btn-close {
  background: var(--color-primary);
  color: white;
  border: none;
}

.btn-close:hover:not(:disabled) {
  background: var(--color-primary-dark);
}

.btn-action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* New Section Form Styles */
.form-row {
  background-color: var(--color-bg-secondary);
}

.new-section-form {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  padding: 0.75rem 0;
}
.error-banner {
  display: flex;
  align-items: center;
  gap: 1rem;
  background-color: #fff5f5;
  border-left: 4px solid #dc3545;
  color: #dc3545;
  padding: 1rem;
  margin-bottom: 1.5rem;
  border-radius: 4px;
  animation: slideIn 0.3s ease-out;
}

.error-icon {
  font-size: 1.2rem;
}

.error-banner p {
  margin: 0;
  flex: 1;
  font-weight: 500;
  font-size: 0.95rem;
}

.error-close {
  background: none;
  border: none;
  color: #dc3545;
  font-size: 1.5rem;
  cursor: pointer;
  line-height: 1;
}

@keyframes slideIn {
  from { opacity: 0; transform: translateY(-10px); }
  to { opacity: 1; transform: translateY(0); }
}


.section-input {
  flex: 1;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--color-border-light);
  border-radius: 0.375rem;
  font-size: 0.875rem;
  background: var(--color-bg-primary);
  color: var(--color-text-primary);
  transition: all 0.2s;
}

.section-input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(13, 110, 253, 0.1);
}

.instructor-select {
  flex: 0 1 auto;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--color-border-light);
  border-radius: 0.375rem;
  font-size: 0.875rem;
  background: var(--color-bg-primary);
  color: var(--color-text-primary);
  transition: all 0.2s;
  cursor: pointer;
  min-width: 180px;
}

.instructor-select:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(13, 110, 253, 0.1);
}

.instructor-select option {
  background: var(--color-bg-primary);
  color: var(--color-text-primary);
}

.btn-submit,
.btn-cancel {
  padding: 0.5rem 1rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
  white-space: nowrap;
}

.btn-submit {
  background: var(--color-primary);
  color: white;
}

.btn-submit:hover:not(:disabled) {
  background: var(--color-primary-dark);
}

.btn-cancel {
  background: transparent;
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border-light);
}

.btn-cancel:hover:not(:disabled) {
  background: var(--color-bg-secondary);
  border-color: var(--color-text-secondary);
}

.btn-submit:disabled,
.btn-cancel:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Delete Button for Sections */
.btn-delete-icon {
  background: none;
  border: none;
  cursor: pointer;
  padding: 0.5rem;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  border-radius: 0.375rem;
}


.btn-delete-icon:hover {
  background: rgba(220, 53, 69, 0.1);
}

.btn-delete-icon:active {
  transform: scale(0.95);
}

.icon-trash {
  width: 20px;
  height: 20px;
  object-fit: contain;
  opacity: 0.7;
  transition: opacity 0.2s;
}

.btn-delete-icon:hover .icon-trash {
  opacity: 1;
}

/* Responsive */

@media (max-width: 768px) {
  .indicator-header {
    flex-direction: column;
  }

  .indicator-meta {
    align-items: flex-start;
  }

  .measure-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .measure-data {
    grid-template-columns: 1fr;
  }

  .data-row.total {
    grid-column: 1;
  }
}
</style>
