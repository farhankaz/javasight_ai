import { NextResponse } from 'next/server'
import clientPromise from '@/lib/mongodb'
import { MongoServerError, ObjectId } from 'mongodb'
import { Kafka, Partitioners } from 'kafkajs'

// Add explicit runtime configuration
export const runtime = 'nodejs'

// Get environment variables with fallbacks
const KAFKA_BROKERS = process.env.KAFKA_BROKERS || 'localhost:9092'

// Initialize Kafka
const kafka = new Kafka({
  clientId: 'javasight-ui',
  brokers: KAFKA_BROKERS.split(','),
})

const producer = kafka.producer({
  createPartitioner: Partitioners.LegacyPartitioner
})
let isProducerConnected = false

// Connect to Kafka lazily
async function ensureProducerConnected() {
  if (!isProducerConnected) {
    await producer.connect()
    isProducerConnected = true
  }
}

// Validate GitHub URL
function isValidGithubUrl(url: string): boolean {
  const githubUrlPattern = /^https:\/\/github\.com\/[\w.-]+\/[\w.-]+(\.git)?$/
  return githubUrlPattern.test(url)
}

export async function GET() {
  try {
    const client = await clientPromise
    const db = client.db()

    // Use aggregation to join projects with their metrics
    const projects = await db.collection('projects').aggregate([
      {
        $addFields: {
          projectIdString: { $toString: '$_id' }
        }
      },
      {
        $lookup: {
          from: 'java_projects_metrics',
          localField: 'projectIdString',
          foreignField: 'projectId',
          as: 'metrics'
        }
      },
      {
        $addFields: {
          latestMetrics: {
            $ifNull: [
              {
                $arrayElemAt: [
                  { $sortArray: { input: '$metrics', sortBy: { timestamp: -1 } } },
                  0
                ]
              },
              {
                moduleCount: 0,
                packageCount: 0,
                fileCount: 0,
                linesOfCode: 0
              }
            ]
          }
        }
      },
      {
        $project: {
          _id: 1,
          projectName: 1,
          projectLocation: 1,
          analysis: 1,
          // Ensure all metrics are numbers with default value 0
          moduleCount: { $convert: { input: '$latestMetrics.moduleCount', to: 'int', onError: 0, onNull: 0 } },
          packageCount: { $convert: { input: '$latestMetrics.packageCount', to: 'int', onError: 0, onNull: 0 } },
          fileCount: { $convert: { input: '$latestMetrics.fileCount', to: 'int', onError: 0, onNull: 0 } },
          linesOfCode: { $convert: { input: '$latestMetrics.linesOfCode', to: 'int', onError: 0, onNull: 0 } }
        }
      }
    ]).toArray()

    return NextResponse.json(projects)
  } catch (e) {
    console.error('MongoDB Error:', e)
    return NextResponse.json(
      { error: 'Failed to fetch projects' },
      { status: 500 }
    )
  }
}

export async function POST(request: Request) {
  try {
    const body = await request.json()
    const { projectName, githubUrl, projectContext = '' } = body

    // Validate required fields
    if (!projectName) {
      return NextResponse.json(
        { error: 'Project name is required' },
        { status: 400 }
      )
    }

    if (!githubUrl) {
      return NextResponse.json(
        { error: 'GitHub URL is required' },
        { status: 400 }
      )
    }

    // Validate GitHub URL format
    if (!isValidGithubUrl(githubUrl)) {
      return NextResponse.json(
        { error: 'Invalid GitHub URL format' },
        { status: 400 }
      )
    }

    const client = await clientPromise
    const db = client.db()

    // Create an import status record
    const importId = new ObjectId()
    
    // Create initial status record
    await db.collection('project_import_status').insertOne({
      _id: importId,
      projectName,
      githubUrl,
      status: 'pending',
      message: 'Import request received',
      progress: 0,
      createdAt: new Date(),
      updatedAt: new Date()
    })

    // Connect to Kafka if not already connected
    await ensureProducerConnected()

    // Send message to Kafka topic - use a simple string value that can be parsed on the Scala side
    await producer.send({
      topic: 'import_github_project_commands',
      messages: [
        {
          key: importId.toString(),
          // Send as plain string that will be parsed on the server side
          value: JSON.stringify({
            project_name: projectName,
            github_url: githubUrl,
            project_context: projectContext || '',
            timestamp: Date.now().toString(),
            import_id: importId.toString()
          })
        }
      ]
    })

    return NextResponse.json({
      success: true,
      message: 'GitHub project import started',
      importId: importId.toString()
    })
  } catch (e) {
    console.error('Error importing GitHub project:', e)
    return NextResponse.json(
      { error: 'Failed to import GitHub project' },
      { status: 500 }
    )
  }
}
