import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { authGuard, roleGuard } from './auth.guard';
import { AuthService } from '../services/auth.service';
import { UserProfile } from '../models/portal.models';

describe('authGuard and roleGuard', () => {
  let router: Router;
  let authStub: {
    isAuthenticated: jasmine.Spy;
    currentUser: jasmine.Spy;
    getHomeRouteForRole: jasmine.Spy;
  };
  const dummyRoute = {} as ActivatedRouteSnapshot;
  const dummyState = {} as RouterStateSnapshot;

  beforeEach(() => {
    authStub = {
      isAuthenticated: jasmine.createSpy('isAuthenticated'),
      currentUser: jasmine.createSpy('currentUser'),
      getHomeRouteForRole: jasmine.createSpy('getHomeRouteForRole').and.returnValue('/customer')
    };

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authStub }
      ]
    });
    router = TestBed.inject(Router);
  });

  it('allows authenticated users through authGuard', () => {
    authStub.isAuthenticated.and.returnValue(true);

    const result = TestBed.runInInjectionContext(() => authGuard(dummyRoute, dummyState));
    expect(result).toBeTrue();
  });

  it('redirects unauthenticated users to /login in authGuard', () => {
    authStub.isAuthenticated.and.returnValue(false);

    const result = TestBed.runInInjectionContext(() => authGuard(dummyRoute, dummyState));
    expect(result instanceof UrlTree).toBeTrue();
    expect(router.serializeUrl(result as UrlTree)).toBe('/login');
  });

  it('redirects users without an allowed role to their home route in roleGuard', () => {
    const customer: UserProfile = {
      id: 'cust-1',
      email: 'customer@capi.test',
      fullName: 'Customer',
      role: 'CUSTOMER',
      userDomain: 'CUSTOMER',
      partnerInstitution: null,
      isLocked: false,
      lockedReason: null
    };
    authStub.isAuthenticated.and.returnValue(true);
    authStub.currentUser.and.returnValue(customer);

    const guard = roleGuard(['ADMIN']);
    const result = TestBed.runInInjectionContext(() => guard(dummyRoute, dummyState));
    expect(result instanceof UrlTree).toBeTrue();
    expect(router.serializeUrl(result as UrlTree)).toBe('/customer');
  });
});

