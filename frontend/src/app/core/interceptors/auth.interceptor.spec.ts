import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: Router;
  let authStub: {
    getToken: jasmine.Spy;
    clearSessionOnly: jasmine.Spy;
  };

  beforeEach(() => {
    authStub = {
      getToken: jasmine.createSpy('getToken').and.returnValue('token-abc'),
      clearSessionOnly: jasmine.createSpy('clearSessionOnly')
    };

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authStub }
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('attaches Bearer token to protected API requests', () => {
    http.get('/api/v1/disputes').subscribe();

    const req = httpMock.expectOne('/api/v1/disputes');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-abc');
    req.flush([]);
  });

  it('omits Bearer token on /api/v1/auth/ endpoints', () => {
    http.post('/api/v1/auth/login', {}).subscribe();

    const req = httpMock.expectOne('/api/v1/auth/login');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('clears session and redirects on 401 from non-login endpoints', () => {
    const navSpy = spyOn(router, 'navigate');

    http.get('/api/v1/disputes').subscribe({
      error: () => {
        // expected 401 error
      }
    });

    const req = httpMock.expectOne('/api/v1/disputes');
    req.flush({ message: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

    expect(authStub.clearSessionOnly).toHaveBeenCalled();
    expect(navSpy).toHaveBeenCalledWith(['/login'], { queryParams: { expired: '1' } });
  });
});

