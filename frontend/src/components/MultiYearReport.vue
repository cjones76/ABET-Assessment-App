<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { BaseCard, BaseSpinner, BaseButton } from '@/components/ui';
import { useMultiYearReportData } from '@/composables/use-multi-year-report-data';
import type { SummaryReportData } from '@/composables/use-summary-report-data';
import SummaryReportTemplate from './SummaryReportTemplate.vue';
import api from '@/api';

interface Props {
  programId: number;
}

interface Semester {
  id: number;
  name: string;
  startDate: string;
  endDate: string;
}

const props = defineProps<Props>();

// ── Semester data ──────────────────────────────────────────────────────────
const allSemesters = ref<Semester[]>([]);
const startSemesterId = ref<number | null>(null);
const endSemesterId = ref<number | null>(null);
const dateError = ref<string | null>(null);
const loadingUI = ref(false);

// ── Load available semesters ───────────────────────────────────────────────
onMounted(async () => {
  try {
    loadingUI.value = true;
    const res = await api.get('/semesters', {
      params: {
      }
    });
    allSemesters.value = (res.data.data ?? [])
      .sort((a: Semester, b: Semester) => new Date(a.startDate).getTime() - new Date(b.startDate).getTime());

    // Auto-select first and last semesters (or within 6-year range)
    if (allSemesters.value.length > 0) {
      startSemesterId.value = allSemesters.value[0].id;
      // Find semesters within 6 years from start
      const startDate = new Date(allSemesters.value[0].startDate);
      const maxDate = new Date(startDate);
      maxDate.setFullYear(maxDate.getFullYear() + 6);

      const semestersInRange = allSemesters.value.filter(
        s => new Date(s.endDate) <= maxDate
      );
      endSemesterId.value = semestersInRange[semestersInRange.length - 1]?.id ?? allSemesters.value[0].id;
    }
  } catch (err) {
    console.error('Failed to load semesters:', err);
  } finally {
    loadingUI.value = false;
  }
});

// ── Get semester details ───────────────────────────────────────────────────
const selectedStartSemester = computed(() =>
  allSemesters.value.find(s => s.id === startSemesterId.value)
);

const selectedEndSemester = computed(() =>
  allSemesters.value.find(s => s.id === endSemesterId.value)
);

// ── Filtered end semester options ──────────────────
const availableEndSemesters = computed(() => {
  if (!selectedStartSemester.value) return allSemesters.value;

  const startDate = new Date(selectedStartSemester.value.startDate);
  const maxDate = new Date(startDate);
  maxDate.setFullYear(maxDate.getFullYear() + 6);

  return allSemesters.value.filter(s =>
    new Date(s.startDate) >= new Date(selectedStartSemester.value!.startDate) &&
    new Date(s.endDate) <= maxDate
  );
});

function validateDates(): boolean {
  dateError.value = null;

  if (!startSemesterId.value || !endSemesterId.value) {
    dateError.value = 'Please select both start and end semesters';
    return false;
  }

  if (!selectedStartSemester.value || !selectedEndSemester.value) {
    dateError.value = 'Invalid semester selection';
    return false;
  }

  if (new Date(selectedEndSemester.value.startDate) < new Date(selectedStartSemester.value.startDate)) {
    dateError.value = 'End semester cannot be before start semester';
    return false;
  }

  return true;
}

// ── Composable ─────────────────────────────────────────────────────────────
const reportProps = computed(() => ({
  programId: props.programId,
  startDate: selectedStartSemester.value?.startDate ?? '',
  endDate:   selectedEndSemester.value?.endDate ?? ''
}));

const { loading, error, reportData, loadReportData } = useMultiYearReportData(reportProps);

function generate() {
  if (!validateDates()) return;
  loadReportData();
}

// ── Local editable copy ───────────────
const reportDataInternal = ref<SummaryReportData | null>(null);

watch(
  () => reportData.value,
  (newVal) => {
    reportDataInternal.value = newVal ? JSON.parse(JSON.stringify(newVal)) : null;
  },
  { immediate: true }
);

// ── Save ────────────────────────────
async function saveReportToBackend(updatedReport: SummaryReportData) {
  for (const outcome of updatedReport.outcomes) {
    for (const indicator of outcome.indicators) {
      for (const measure of indicator.measures) {
        await api.put(`/measure/${measure.measureId}`, {
          id: measure.measureId,
          courseIndicatorId: measure.courseIndicatorId,
          description: measure.description,
          observation: measure.note ?? null,
          recAction: measure.recommendedAction ?? null,
          met: measure.studentsMet ?? 0,
          exceeded: measure.studentsExceeded ?? 0,
          below: measure.studentsBelow ?? 0,
          metPercentage: measure.metPercentage ?? 0,
          status: 'Complete',
          active: true
        });
      }
    }
  }
  await loadReportData();
}
</script>

<template>
  <div class="multi-year-report">

    <!-- Semester Range Selector -->
    <BaseCard class="date-selector-card">
      <h3>Select Semester Range</h3>
      <div class="date-inputs">
        <div class="date-field">
          <label for="start-semester">Start Semester</label>
          <select
            id="start-semester"
            v-model.number="startSemesterId"
            :disabled="loadingUI || allSemesters.length === 0"
          >
            <option :value="null" disabled>-- Select start semester --</option>
            <option
              v-for="sem in allSemesters"
              :key="sem.id"
              :value="sem.id"
            >
              {{ sem.name }} ({{ sem.startDate }})
            </option>
          </select>
        </div>

        <div class="date-field">
          <label for="end-semester">End Semester</label>
          <select
            id="end-semester"
            v-model.number="endSemesterId"
            :disabled="!startSemesterId || loadingUI || availableEndSemesters.length === 0"
          >
            <option :value="null" disabled>-- Select end semester --</option>
            <option
              v-for="sem in availableEndSemesters"
              :key="sem.id"
              :value="sem.id"
            >
              {{ sem.name }} ({{ sem.endDate }})
            </option>
          </select>
        </div>

        <BaseButton variant="primary" @click="generate" :disabled="loading || loadingUI">
          Generate Report
        </BaseButton>
      </div>

      <p v-if="dateError" class="error-text">{{ dateError }}</p>
    </BaseCard>

    <!-- Loading -->
    <div v-if="loading" class="loading-state">
      <BaseSpinner size="lg" text="Generating multi-year report..." />
    </div>

    <!-- Error -->
    <div v-else-if="error" class="error-state">
      <p>{{ error }}</p>
      <BaseButton variant="primary" @click="generate">Retry</BaseButton>
    </div>

    <!-- Report -->
    <div v-else-if="reportDataInternal">
      <SummaryReportTemplate
        v-model:report="reportDataInternal"
        @save="saveReportToBackend"
        @import="() => console.log('import!')"
        @regenerate="generate"
        @reload="generate"
      />
    </div>

    <!-- Empty -->
    <div v-else-if="startSemesterId && endSemesterId" class="empty-state">
      <p>No report data available for the selected semester range.</p>
    </div>

  </div>
</template>

<style scoped>
.multi-year-report {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.date-selector-card h3 {
  margin: 0 0 1rem;
  font-size: 1rem;
  font-weight: 600;
}

.date-inputs {
  display: flex;
  align-items: flex-end;
  gap: 1rem;
  flex-wrap: wrap;
}

.date-field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.date-field label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-text-secondary);
}

.date-field select {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--color-border-light);
  border-radius: 0.375rem;
  font-size: 0.9rem;
  background: var(--color-bg-primary);
  color: var(--color-text-primary);
  min-width: 200px;
}

.date-field select:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.date-range-display {
  margin-top: 1rem;
  padding: 0.75rem;
  background: var(--color-background-soft);
  border-radius: 0.375rem;
}

.range-text {
  margin: 0;
  font-size: 0.9rem;
  color: var(--color-text-secondary);
}

.error-text {
  margin-top: 0.5rem;
  font-size: 0.875rem;
  color: var(--color-error);
}

.loading-state,
.error-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  gap: 1rem;
}

.error-state {
  color: var(--color-error);
}

@media (max-width: 640px) {
  .date-inputs {
    flex-direction: column;
    align-items: stretch;
  }

  .date-field select {
    min-width: unset;
  }
}
</style>
