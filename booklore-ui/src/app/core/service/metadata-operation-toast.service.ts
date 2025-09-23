import {inject, Injectable} from '@angular/core';
import {MessageService} from 'primeng/api';

@Injectable({providedIn: 'root'})
export class MetadataOperationToastService {

  private readonly key = 'metadata-operations';
  private readonly defaultProgressSummary = 'Saving Metadata';
  private readonly defaultProgressDetail = 'Large comic archives may take a moment.';

  private readonly defaultSuccessSummary = 'Metadata Saved';
  private readonly defaultSuccessDetail = 'Metadata saved successfully.';

  private readonly defaultErrorSummary = 'Save Failed';
  private readonly defaultErrorDetail = 'We could not finish saving the metadata. Please review the error and try again.';

  private messageService = inject(MessageService);

  start(progressDetail?: string, progressSummary?: string): void {
    this.messageService.clear(this.key);
    this.messageService.add({
      key: this.key,
      severity: 'info',
      summary: progressSummary ?? this.defaultProgressSummary,
      detail: progressDetail ?? this.defaultProgressDetail,
      sticky: true,
      closable: true,
    });
  }

  success(detail?: string, summary?: string): void {
    this.messageService.clear(this.key);
    this.messageService.add({
      key: this.key,
      severity: 'success',
      summary: summary ?? this.defaultSuccessSummary,
      detail: detail ?? this.defaultSuccessDetail,
      sticky: false,
      closable: true,
    });
  }

  error(detail?: string, summary?: string): void {
    this.messageService.clear(this.key);
    this.messageService.add({
      key: this.key,
      severity: 'error',
      summary: summary ?? this.defaultErrorSummary,
      detail: detail ?? this.defaultErrorDetail,
      sticky: false,
      closable: true,
    });
  }

  clear(): void {
    this.messageService.clear(this.key);
  }
}

