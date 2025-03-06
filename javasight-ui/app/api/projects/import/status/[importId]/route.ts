import { NextRequest } from 'next/server'
import clientPromise from '@/lib/mongodb'
import { ObjectId } from 'mongodb'

// Add explicit runtime configuration
export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

// Helper to format SSE messages
function formatSSE(data: any) {
  return `data: ${JSON.stringify(data)}\n\n`
}

// Export GET handler with streaming response
export async function GET(
  request: NextRequest,
  { params }: { params: { importId: string } }
) {
  // Use destructuring from params directly which is safer in Next.js
  const { importId: importIdStr } = params;

  // Validate importId format
  let objectId: ObjectId;
  try {
    objectId = new ObjectId(importIdStr);
  } catch (e) {
    return new Response(
      formatSSE({
        type: 'error',
        status: 'failed',
        message: 'Invalid import ID format',
        progress: 0,
        error: 'The provided import ID is not valid'
      }),
      {
        headers: {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache, no-transform',
          'Connection': 'keep-alive',
        },
      }
    );
  }

  // Create a readable stream for SSE
  const stream = new ReadableStream({
    async start(controller) {
      // Send the initial connection message
      controller.enqueue(
        formatSSE({
          type: 'connection',
          status: 'pending',
          message: 'Connected to import status updates',
          progress: 0
        })
      );

      try {
        const client = await clientPromise;
        const db = client.db();
        const statusCollection = db.collection('project_import_status');

        // Helper to send the current status
        const sendStatus = async () => {
          try {
            const status = await statusCollection.findOne({ _id: objectId });
            
            if (!status) {
              controller.enqueue(
                formatSSE({
                  type: 'error',
                  status: 'failed',
                  message: 'Import not found',
                  progress: 0,
                  error: 'No import with the provided ID exists'
                })
              );
              return false; // Stop polling
            }
            
            // Send the current status
            controller.enqueue(
              formatSSE({
                type: 'status',
                status: status.status,
                message: status.message,
                progress: status.progress,
                error: status.error
              })
            );
            
            // If status is completed or failed, stop polling
            return status.status !== 'completed' && status.status !== 'failed';
          } catch (error) {
            console.error('Error getting import status:', error);
            controller.enqueue(
              formatSSE({
                type: 'error',
                status: 'failed',
                message: 'Failed to retrieve import status',
                progress: 0,
                error: error instanceof Error ? error.message : 'Unknown error'
              })
            );
            return false; // Stop polling
          }
        };

        // Poll for updates (initial status and then every 2 seconds)
        let shouldContinue = await sendStatus();
        
        const interval = setInterval(async () => {
          shouldContinue = await sendStatus();
          
          if (!shouldContinue) {
            clearInterval(interval);
            // Wait a moment to ensure the final status is sent before closing
            setTimeout(() => controller.close(), 1000);
          }
        }, 2000);

        // Handle client disconnect
        request.signal.addEventListener('abort', () => {
          clearInterval(interval);
          controller.close();
        });
      } catch (error) {
        console.error('Failed to set up SSE connection:', error);
        controller.enqueue(
          formatSSE({
            type: 'error',
            status: 'failed',
            message: 'Failed to set up status tracking',
            progress: 0,
            error: error instanceof Error ? error.message : 'Unknown error'
          })
        );
        controller.close();
      }
    }
  });

  // Return the stream as an SSE response
  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive',
    },
  });
}