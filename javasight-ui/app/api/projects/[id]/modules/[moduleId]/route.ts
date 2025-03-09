import { NextResponse } from 'next/server';
import clientPromise from '@/lib/mongodb';
import { ObjectId } from 'mongodb';

export async function GET(
  request: Request,
  context: { params: Promise<{ id: string, moduleId: string }> }
) {
  try {
    const params = await context.params;
    const { id, moduleId } = params;

    const client = await clientPromise;
    const db = client.db('javasight');

    const moduleAndPackages = await db.collection('modules').aggregate([
      {
        $match: {
          _id: new ObjectId(moduleId)
        }
      },
      {
        $addFields: {
          id: { $toString: "$_id" }
        }
      },
      {
        $lookup: {
          from: 'packages',
          localField: 'id',
          foreignField: 'module_id',
          as: 'packages'
        }
      },
      {
        $lookup: {
          from: 'modules_metrics',
          localField: 'id',
          foreignField: 'moduleId',
          as: 'metrics'
        }
      },
      {
        $addFields: {
          metrics: { $arrayElemAt: ['$metrics', 0] }
        }
      },
      {
        $unwind: {
          path: "$packages",
          preserveNullAndEmptyArrays: true
        }
      },
      {
        $lookup: {
          from: 'packages_metrics',
          let: { packageId: { $toString: "$packages._id" } },
          pipeline: [
            {
              $match: {
                $expr: { $eq: ["$packageId", "$$packageId"] }
              }
            }
          ],
          as: 'packages.metrics'
        }
      },
      {
        $addFields: {
          "packages.metrics": { $arrayElemAt: ["$packages.metrics", 0] }
        }
      },
      {
        $group: {
          _id: "$_id",
          moduleName: { $first: "$moduleName" },
          analysis: { $first: "$analysis" },
          metrics: { $first: "$metrics" },
          packages: { $push: "$packages" }
        }
      }
    ])
    .toArray();

    // Return the first (and should be only) module or null if not found
    return NextResponse.json(moduleAndPackages[0] || null);
  } catch (error) {
    console.error('Error details:', {
      name: error.name,
      message: error.message,
      stack: error.stack
    });
    
    if (error instanceof Error && error.message.includes('ObjectId')) {
      return NextResponse.json({ error: 'Invalid ID format' }, { status: 400 });
    }
    return NextResponse.json({ error: 'Internal Server Error' }, { status: 500 });
  }
} 