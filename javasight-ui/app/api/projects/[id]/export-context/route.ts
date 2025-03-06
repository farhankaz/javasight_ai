import { NextRequest, NextResponse } from 'next/server';
import clientPromise from '@/lib/mongodb';
import { ObjectId } from 'mongodb';

export async function GET(
  request: NextRequest,
  context: { params: { id: string } }
) {
  try {
    const { id } = context.params;
    const client = await clientPromise;
    const db = client.db();
    
    // Fetch project data
    const project = await db.collection('projects').findOne({
      _id: new ObjectId(id)
    });
    
    if (!project) {
      return NextResponse.json({ error: 'Project not found' }, { status: 404 });
    }
    
    // Fetch modules for this project
    const modules = await db.collection('java_modules')
      .find({ projectId: id })
      .toArray();
    
    // Initialize markdown content with project information
    let markdownContent = `# ${project.projectName} - JavaSight Analysis Context\n\n`;
    markdownContent += `## Project: ${project.projectName}\n\n`;
    markdownContent += `${project.analysis || 'No analysis available'}\n\n`;
    
    // For each module, fetch packages and generate markdown
    for (const module of modules) {
      markdownContent += `## Module: ${module.moduleName}\n\n`;
      markdownContent += `${module.analysis || 'No analysis available'}\n\n`;
      
      // Fetch packages for this module
      const packages = await db.collection('java_packages')
        .find({ module_id: module._id.toString() })
        .toArray();
      
      for (const pkg of packages) {
        markdownContent += `### Package: ${pkg.packageName}\n\n`;
        markdownContent += `${pkg.analysis || 'No analysis available'}\n\n`;
        
        // Fetch files for this package
        const files = await db.collection('java_files')
          .find({ packageId: pkg._id.toString() })
          .toArray();
        
        for (const file of files) {
          const fileName = file.filePath.split('/').pop();
          markdownContent += `#### File: ${fileName}\n\n`;
          markdownContent += `${file.shortAnalysis || 'No analysis available'}\n\n`;
        }
      }
    }
    
    // Return the markdown content directly with appropriate headers
    return new NextResponse(markdownContent, {
      headers: {
        'Content-Type': 'text/markdown',
        'Content-Disposition': `attachment; filename="context.md"`,
      },
    });
    
  } catch (error) {
    console.error('Error generating context export:', error);
    return NextResponse.json({ error: 'Failed to generate context export' }, { status: 500 });
  }
}