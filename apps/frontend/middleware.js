import { NextResponse } from 'next/server';

function getClientIp(request) {
  const forwardedFor = request.headers.get('x-forwarded-for');
  if (forwardedFor) {
    return forwardedFor.split(',')[0].trim();
  }

  return request.headers.get('x-real-ip') || 'unknown';
}

export function middleware(request) {
  const { pathname, search } = request.nextUrl;

  console.info(
    `[frontend] ${request.method} ${pathname}${search} ip=${getClientIp(request)} ua="${request.headers.get('user-agent') || 'unknown'}"`
  );

  return NextResponse.next();
}

export const config = {
  matcher: [
    '/((?!_next/static|_next/image|favicon.ico|images/|.*\\.(?:svg|png|jpg|jpeg|gif|webp|ico|css|js|map|txt)$).*)'
  ]
};
