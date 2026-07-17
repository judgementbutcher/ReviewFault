@Observed
export class ReviewFaultUiState {
  ready: boolean = false;
  screen: string = 'home';
  loading: boolean = true;
  errorMessage: string = '';
}
