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
  { params }: { params: { id: string; moduleId: string } }
) {
  // In Next.js route handlers, destructure the params
  const { id, moduleId } = params;
  
  try {
    // Connect to Kafka
    await producer.connect();
    
    // Create a binary message in the proper protobuf format
    // Field 1: module_id (string)
    const moduleIdEncoded = Buffer.concat([
      // Field number 1, wire type 2 (length-delimited)
      Buffer.from([0x0A]),
      // Length of the string value
      Buffer.from([moduleId.length]),
      // The actual string value
      Buffer.from(moduleId, 'utf8')
    ]);
    
    // Field 2: project_id (string)
    const projectIdEncoded = Buffer.concat([
      // Field number 2, wire type 2 (length-delimited)
      Buffer.from([0x12]),
      // Length of the string value
      Buffer.from([id.length]),
      // The actual string value
      Buffer.from(id, 'utf8')
    ]);
    
    // Field 3: timestamp (int64)
    const timestamp = Date.now();
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
    
    // Send the message to Kafka
    await producer.send({
      topic: 'analyze_module_commands',
      messages: [
        { 
          value: messageBuffer 
        },
      ],
    });
    
    // Disconnect from Kafka
    await producer.disconnect();
    
    // Return success response
    return NextResponse.json({ 
      success: true, 
      message: 'Module analysis requested successfully' 
    });
  } catch (error) {
    console.error('Error triggering module analysis:', error);
    
    // Ensure producer is disconnected in case of error
    try {
      await producer.disconnect();
    } catch (disconnectError) {
      console.error('Error disconnecting producer:', disconnectError);
    }
    
    // Return error response
    return NextResponse.json(
      { 
        success: false, 
        error: error instanceof Error ? error.message : 'An unknown error occurred' 
      },
      { status: 500 }
    );
  }
}