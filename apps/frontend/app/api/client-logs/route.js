export async function POST(request) {
  try {
    const payload = await request.json();
    const forwardedFor = request.headers.get('x-forwarded-for');
    const ip = forwardedFor ? forwardedFor.split(',')[0].trim() : request.headers.get('x-real-ip') || 'unknown';

    console.info(
      `[frontend-client] level=${payload?.level || 'info'} path=${payload?.path || 'unknown'} ip=${ip} args=${JSON.stringify(payload?.args || [])}`
    );

    return Response.json({ success: true });
  } catch (error) {
    console.warn(`[frontend-client] log ingestion failed: ${error instanceof Error ? error.message : 'unknown error'}`);
    return Response.json({ success: false }, { status: 400 });
  }
}
