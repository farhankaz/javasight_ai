import { NextRequest, NextResponse } from 'next/server';
import { Kafka } from 'kafkajs';

// Initialize Kafka client
const kafka = new Kafka({
  clientId: 'javasight-ui',
  brokers: ['localhost:9092'], // This should match the Kafka broker in your environment
});

// Initialize producer
const producer = kafka.producer();

export async function POST(
  request: NextRequest,
  { params }: { params: { id: string } }
) {
  try {
    // Connect to Kafka
    await producer.connect();

    // Get project information to retrieve module IDs
    // Use absolute URL with origin for API routes
    const origin = process.env.NEXT_PUBLIC_API_URL ||
                  (process.env.VERCEL_URL ? `https://${process.env.VERCEL_URL}` :
                  'http://localhost:3000');
    
    const projectId = params.id;
    const projectResponse = await fetch(`${origin}/api/projects/${projectId}`);
    
    if (!projectResponse.ok) {
      throw new Error('Failed to fetch project data');
    }
    
    const projectData = await projectResponse.json();
    const modules = projectData.modules || [];

    if (modules.length === 0) {
      return NextResponse.json({ message: 'No modules found to analyze' }, { status: 400 });
    }

    // Create a timestamp that will be used for all messages
    const timestamp = Date.now();
    
    // Create and send a message for each module
    const kafkaMessages = modules.map(module => {
      // Manual protobuf encoding for AnalyzeModuleCommand
      
      // Field 1: module_id (string)
      const moduleIdEncoded = Buffer.concat([
        // Field number 1, wire type 2 (length-delimited)
        Buffer.from([0x0A]),
        // Length of the string value
        Buffer.from([module._id.length]),
        // The actual string value
        Buffer.from(module._id, 'utf8')
      ]);
      
      // Field 2: project_id (string)
      const projectIdEncoded = Buffer.concat([
        // Field number 2, wire type 2 (length-delimited)
        Buffer.from([0x12]),
        // Length of the string value
        Buffer.from([projectId.length]),
        // The actual string value
        Buffer.from(projectId, 'utf8')
      ]);
      
      // Field 3: timestamp (int64)
      const timestampEncoded = Buffer.concat([
        // Field number 3, wire type 0 (varint)
        Buffer.from([0x18]),
        // Variable-length encoding of the timestamp (simplified)
        Buffer.from([(timestamp & 0x7F) | 0x80, ((timestamp >> 7) & 0x7F) | 0x80,
                     ((timestamp >> 14) & 0x7F) | 0x80, ((timestamp >> 21) & 0x7F) | 0x80,
                     ((timestamp >> 28) & 0x7F) | 0x80, ((timestamp >> 35) & 0x7F) | 0x80,
                     ((timestamp >> 42) & 0x7F) | 0x80, ((timestamp >> 49) & 0x7F) | 0x80,
                     ((timestamp >> 56) & 0x7F)])
      ]);
      
      // Combine all fields into a single protobuf message
      const messageBuffer = Buffer.concat([
        moduleIdEncoded,
        projectIdEncoded,
        timestampEncoded
      ]);
      
      return { 
        value: messageBuffer 
      };
    });

    // Send all messages to Kafka
    await producer.send({
      topic: 'analyze_module_commands',
      messages: kafkaMessages
    });

    await producer.disconnect();

    return NextResponse.json({
      success: true,
      message: `Analysis of all ${modules.length} modules has been triggered`,
      moduleCount: modules.length
    });
  } catch (error) {
    console.error('Error analyzing project:', error);
    
    // Ensure producer is disconnected in case of error
    try {
      await producer.disconnect();
    } catch (e) {
      console.error('Error disconnecting producer:', e);
    }
    
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : 'Unknown error occurred' },
      { status: 500 }
    );
  }
}