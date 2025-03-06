import { NextResponse } from 'next/server'
import clientPromise from '@/lib/mongodb'
import { MongoServerError } from 'mongodb'

// Add explicit runtime configuration
export const runtime = 'nodejs'

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