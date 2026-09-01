<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import api from '@/api';
import { BaseCard, BaseSpinner, BaseButton, BaseSelect } from '@/components/ui';
import { useSummaryReportData } from '@/composables/use-summary-report-data';
import type { SummaryReportData } from '@/composables/use-summary-report-data';
import SummaryReportTemplate from "./SummaryReportTemplate.vue";

interface Props {
  programId: number;
  semesterId?: number;
  showExportButton?: boolean;
  showSemesterSelector?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  showExportButton: true,
  showSemesterSelector: true
});

// Semester selection
interface Semester {
  id: number;
  name: string;
  code: string;
  academicYear: number;
  isCurrent: boolean;
}

const semesters = ref<Semester[]>([]);
const selectedSemesterId = ref<number | null>(null);
const loadingSemesters = ref(false);
const semesterError = ref<string | null>(null);

// Use provided semesterId or selected one
const activeSemesterId = computed(() => {
  if (props.semesterId) return props.semesterId;
  return selectedSemesterId.value;
});

// Load report data using composable
const {
  loading,
  error,
  reportData,
  loadReportData
} = useSummaryReportData({
  programId: props.programId,
  semesterId: activeSemesterId
});

// Semester dropdown options
const semesterOptions = computed(() =>
  semesters.value.map(s => ({
    label: `${s.name} (${s.academicYear}-${s.academicYear + 1})`,
    value: s.id
  }))
);

// Local copy of report data for editing
const reportDataInternal = ref<SummaryReportData | null>(null);

watch(
  () => reportData.value,
  (newVal) => {
    reportDataInternal.value = newVal
      ? JSON.parse(JSON.stringify(newVal))
      : null;
  },
  { immediate: true }
);

// Load semesters for selector
async function loadSemesters() {
  if (!props.showSemesterSelector || props.semesterId) return;

  loadingSemesters.value = true;
  semesterError.value = null;

  try {
    const res = await api.get('/semesters', {
      params: {
        programId: props.programId
      }
    });

    semesters.value = Array.isArray(res.data.data) ? res.data.data : (res.data.content || []);

    const current = semesters.value.find(s => s.isCurrent);

    if (current) {
      selectedSemesterId.value = current.id;
    } else if (semesters.value.length > 0) {
      selectedSemesterId.value = semesters.value[0].id;
    }

    if (selectedSemesterId.value && props.programId) {
      loadReportData();
    }

  } catch (err) {
    console.error('Error loading semesters:', err);
    semesterError.value = 'Failed to load semesters';
  } finally {
    loadingSemesters.value = false;
  }
}

// Save report changes back to database
async function saveReportToBackend(updatedReport: SummaryReportData) {
  try {
    // Update all measures in the report
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
            status: "Complete",
            active: true
          });
        }
      }
    }

    // Reload the report data to reflect saved changes
    await loadReportData();

    console.log('Report saved successfully');
  } catch (err) {
    console.error('Failed to save report:', err);
    throw err;
  }
}

// Lifecycle
watch(activeSemesterId, (newId) => {
  console.log('Semester changed to:', newId);
  if (newId && props.programId) {
    loadReportData();
  }
});

watch(() => props.programId, () => {
  if (props.programId && activeSemesterId.value) {
    loadReportData();
  }
});

onMounted(() => {
  loadSemesters();

  // Load report if we have both programId and semesterId
  if (props.programId && activeSemesterId.value) {
    loadReportData();
  }
});
</script>

<template>
  <div class="summary-report">
    <!-- Semester Selector -->
    <BaseCard
      v-if="showSemesterSelector && !props.semesterId"
      class="semester-selector-card"
    >
      <div class="selector-content">
        <BaseSelect
          v-model="selectedSemesterId"
          :options="semesterOptions"
          label="Select Academic Year"
          placeholder="Choose a semester..."
          :disabled="loadingSemesters || semesters.length === 0"
        />

        <div v-if="loadingSemesters" class="loading-text">
          Loading semesters...
        </div>

        <div v-else-if="semesterError" class="error-text">
          {{ semesterError }}
        </div>

        <div v-else-if="semesters.length === 0" class="empty-text">
          No semesters available for this program
        </div>
      </div>
    </BaseCard>

    <!-- Loading State -->
    <div v-if="loading" class="loading-state">
      <BaseSpinner size="lg" text="Generating summary report..." />
    </div>

    <!-- Error -->
    <div v-else-if="error" class="error-state">
      <p>{{ error }}</p>
      <BaseButton variant="primary" @click="loadReportData">
        Retry
      </BaseButton>
    </div>

    <!-- Unified Renderer -->
    <div v-else-if="reportDataInternal">
      <SummaryReportTemplate
        v-model:report="reportDataInternal"
        @save="saveReportToBackend"
        @import="() => console.log('import!')"
        @regenerate="loadReportData"
        @reload="loadReportData"
      />
    </div>

    <!-- Empty State -->
    <div v-else class="empty-state">
      <p>
        {{ showSemesterSelector && !props.semesterId
        ? 'Select a semester to view the report'
        : 'No report data available'
        }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.summary-report {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

/* Semester Selector */
.semester-selector-card {
  margin-bottom: 1rem;
}

.selector-content {
  max-width: 500px;
}

.loading-text,
.error-text,
.empty-text {
  margin-top: 0.5rem;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.error-text {
  color: var(--color-error);
}

/* Loading/Error States */
.loading-state,
.error-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  gap: 1rem;
}

.error-state {
  color: var(--color-error);
}

/* Report Header */
.header-content h1 {
  margin: 0 0 0.5rem 0;
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--color-text-primary);
}

/* Recommended Actions */
.recommended-actions h3 {
  margin: 0 0 0.75rem 0;
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.recommended-actions ul {
  margin: 0;
  padding-left: 1.5rem;
  list-style-type: disc;
}

.recommended-actions li {
  margin: 0.5rem 0;
  font-size: 0.9rem;
  line-height: 1.6;
  color: var(--color-text-primary);
}

/* Responsive */
@media (max-width: 768px) {
  .selector-content {
    max-width: 100%;
  }
}

.pdf-export-wrapper * {
  break-inside: avoid;
  page-break-inside: avoid;
}

@media print {
  .header-actions,
  .semester-selector-card {
    display: none !important;
  }

  .pdf-export-wrapper {
    padding: 0;
  }
}

.summary-report {
  gap: 1rem;
}
</style>
